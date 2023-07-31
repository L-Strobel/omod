package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import de.uniwuerzburg.omod.io.*
import de.uniwuerzburg.omod.routing.RoutingCache
import de.uniwuerzburg.omod.routing.RoutingMode
import de.uniwuerzburg.omod.routing.createGraphHopper
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import org.locationtech.jts.index.kdtree.KdTree
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.collections.ArrayList

/**
 * Open-Street-Maps MObility Demand generator (OMOD)
 * Creates daily activity schedules in the form of activity chains and dwell times.
 *
 * @param areaFile GeoJSON of the focus area
 * @param osmFile osm.pbf file that covers at least the focus area and buffer area
 * @param mode Method of distance calculation used in the simulation
 * @param cache Option to cache the routing matrix for subsequent runs
 * @param cacheDir Directory where the cache should be located
 * @param odFile Origin-Destination Matrix in GeoJSON format. Optional input used for calibration.
 * @param gridPrecision Precision of the routing grid
 * @param seed Random number seed
 * @param bufferRadius Distance with which the focus area is buffered to obtain the buffer area
 * @param censusFile GeoJSON containing census information (Population distribution in the model area)
 * @param populateBufferArea Option to populate the buffer area with agents
 * @param distanceCacheSize Maximum size of the routing matrix cache
 * @param populationFile File that defines the distribution of socio-demographic features for the agent population
 */
class Omod(
    areaFile: File,
    osmFile: File,
    mode: RoutingMode = RoutingMode.BEELINE,
    cache: Boolean = true,
    cacheDir: Path = Paths.get("omod_cache/"),
    odFile: File? = null,
    gridPrecision: Double = 200.0,
    seed: Long? = null,
    bufferRadius: Double = 0.0,
    censusFile: File? = null,
    private val populateBufferArea: Boolean = true,
    distanceCacheSize: Int = 20_000,
    populationFile: File? = null
) {
    @Suppress("MemberVisibilityCanBePrivate")
    val kdTree: KdTree
    @Suppress("MemberVisibilityCanBePrivate")
    val buildings: List<Building>
    val hopper: GraphHopper?
    private val grid: List<Cell>
    private val zones: List<LocationOption> // Grid + DummyLocations for commuting locations
    private val firstOrderCFactors = mutableMapOf<ActivityType, Map<ODZone, Double>>()
    private val secondOrderCFactors = mutableMapOf<Pair<ActivityType, ActivityType>, Map<Pair<ODZone, ODZone>, Double>>()
    private val populationDef: PopulationDef
    private val activityDataStore: ActivityDataStore
    private val locChoiceWeightFuns: Map<ActivityType, LocationChoiceDCWeightFun>
    private val rng: Random = if (seed != null) Random(seed) else Random()
    private val routingCache: RoutingCache
    private val geometryFactory: GeometryFactory = GeometryFactory()
    val transformer: CRSTransformer
    private val logger = LoggerFactory.getLogger(Omod::class.java)
    private var censusAvailable = false

    init {
        // Get population distribution
        val popTxt = populationFile?.readText(Charsets.UTF_8)
            ?: Omod::class.java.classLoader.getResource("Population.json")!!.readText(Charsets.UTF_8)
        populationDef = Json.decodeFromString(popTxt)

        // Get activity chain data
        val actTxt = Omod::class.java.classLoader.getResource("ActivityGroups.json")!!.readText(Charsets.UTF_8)
        val activityGroups: List<ActivityGroup> = Json.decodeFromString(actTxt)
        activityDataStore = ActivityDataStore(activityGroups)

        // Get distance distributions
        val distrTxt = Omod::class.java.classLoader.getResource("LocChoiceWeightFuns.json")!!.readText(Charsets.UTF_8)
        val mutLocChoiceFuns: MutableMap<ActivityType, LocationChoiceDCWeightFun> = Json.decodeFromString(distrTxt)
        if (censusFile != null) {
            censusAvailable = true
            mutLocChoiceFuns[ActivityType.HOME] = ByPopulation
        }
        mutLocChoiceFuns[ActivityType.BUSINESS] = mutLocChoiceFuns[ActivityType.OTHER]!!
        locChoiceWeightFuns = mutLocChoiceFuns.toMap()

        // Load focus area
        val focusArea = getFocusArea(areaFile)

        // Get CRSTransformer
        val center = focusArea.centroid
        transformer = CRSTransformer( center.coordinate.y )

        // Get spatial data
        buildings = getBuildings(focusArea, geometryFactory, transformer, osmFile, cacheDir,
                                 bufferRadius, censusFile, cache)

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building)
        }

        // Create grid (used for speed up)
        grid = makeClusterGrid(gridPrecision, buildings, geometryFactory, transformer)
        for (cell in grid) {
            cell.buildings.forEach { it.cell = cell }
        }

        // Create graphhopper
        hopper = if (mode == RoutingMode.GRAPHHOPPER) {
            createGraphHopper(
                    osmFile.toString(),
                    Paths.get(cacheDir.toString(), "routing-graph-cache", osmFile.name).toString()
            )
        } else {
            null
        }

        // Create routing cache
        routingCache = RoutingCache(mode, hopper, distanceCacheSize)
        if (mode == RoutingMode.GRAPHHOPPER) {
            val priorityValues = getWeightsNoOrigin(grid, ActivityType.OTHER) // Priority of cells for caching
            routingCache.load(grid, cacheDir, priorityValues)
        }

        // Calibration
        if (odFile != null) {
            logger.info("Calibrating with OD-Matrix...")
            // Read OD-Matrix that is used for calibration
            val odZones = ODZone.readODMatrix(odFile, geometryFactory, transformer)
            // Add TAZ to buildings and cells
            val dummyZones = addODZoneInfo(odZones)
            zones = grid + dummyZones
            // Get calibration factors based on OD-Matrix
            val (kfo, vfo) = calcFirstOrderScaling(odZones)
            firstOrderCFactors[kfo] = vfo
            val (kso, vso) =  calcSecondOrderScaling(odZones)
            secondOrderCFactors[kso] = vso
            logger.info("Calibration done!")
        } else {
            zones = grid
        }
    }

    // Factories
    companion object {
        /**
         * Create OMOD object with default parameters
         *
         * @param areaFile GeoJSON of the focus area
         * @param osmFile osm.pbf file that covers at least the focus area and buffer area
         */
        @Suppress("unused")
        fun defaultFactory(areaFile: File, osmFile: File): Omod {
            return Omod(areaFile, osmFile)
        }
    }

    /**
     * Parse the areaFile
     */
    private fun getFocusArea(areaFile: File): Geometry {
        val areaColl: GeoJsonNoProperties = json.decodeFromString(areaFile.readText(Charsets.UTF_8))
        return if (areaColl is GeoJsonFeatureCollectionNoProperties) {
            geometryFactory.createGeometryCollection(
                areaColl.features.map { it.geometry.toJTS(geometryFactory) }.toTypedArray()
            ).union()
        } else {
            (areaColl as GeoJsonGeometryCollection).toJTS(geometryFactory).union()
        }
    }

    /**
     * Obtain buildings from input data
     */
    private fun getBuildings(focusArea: Geometry, geometryFactory: GeometryFactory, transformer: CRSTransformer,
                     osmFile: File, cacheDir: Path, bufferRadius: Double = 0.0, censusFile: File?,
                     cache: Boolean) : List<Building> {
        // Is cached?
        val bound = focusArea.envelopeInternal
        val cachePath = Paths.get(cacheDir.toString(),
            "AreaBounds${listOf(bound.minX, bound.maxX, bound.minY, bound.maxY)
                .toString().replace(" ", "")}" +
                    "Buffer${bufferRadius}" +
                    "Census${censusFile?.nameWithoutExtension ?: false}" +
                    ".geojson"
        )

        // Check cache
        val collection: GeoJsonFeatureCollection
        if (cache and cachePath.toFile().exists()) {
            collection = json.decodeFromString(cachePath.toFile().readText(Charsets.UTF_8))
        } else {
            // Load data from geojson files and PostgreSQL database with OSM data
            collection = buildArea(
                focusArea = focusArea,
                osmFile = osmFile,
                bufferRadius = bufferRadius,
                censusFile = censusFile,
                transformer = transformer,
                geometryFactory = geometryFactory
            )

            if (cache) {
                Files.createDirectories(cachePath.parent)
                cachePath.toFile().writeText(json.encodeToString(collection))
            }
        }
        return Building.fromGeoJson(collection, geometryFactory, transformer)
    }

    /**
     * Determine the OD-Zone of each building and cell. Creates dummy locations if necessary.
     */
    private fun addODZoneInfo(odZones: List<ODZone>) : List<DummyLocation> {
        val dummyZones = mutableListOf<DummyLocation>()

        // Get buildings in every TAZ and add the TAZ to that building
        for (odZone in odZones) {
            val zoneBuildings = mutableListOf<Building>()

            fastCovers(odZone.geometry, listOf(10000.0, 5000.0, 1000.0), geometryFactory,
                ifNot = { },
                ifDoes = { e ->
                    zoneBuildings.addAll(kdTree.query(e).map { ((it as KdNode).data as Building) })
                },
                ifUnsure = { e ->
                    zoneBuildings.addAll(kdTree.query(e).map { ((it as KdNode).data as Building) }
                        .filter { odZone.geometry.contains(it.point) })
                }
            )

            val activities = setOf(odZone.originActivity, odZone.destinationActivity)

            if (zoneBuildings.isEmpty()) {
                val centroid =  odZone.geometry.centroid
                val latlonCoord = transformer.toLatLon( centroid ).coordinate
                // Create dummy node for TAZ
                val dummyLoc = DummyLocation(
                    coord = centroid.coordinate,
                    latlonCoord = latlonCoord,
                    odZone = odZone,
                    transferActivities = activities
                )
                dummyZones.add(dummyLoc)
                odZone.aggLocs.add(dummyLoc)
            } else {
                for (building in zoneBuildings) {
                    // Remember ODZone
                    building.odZone = odZone
                }
                // Is zone in focus area?
                odZone.inFocusArea = zoneBuildings.any{ it.inFocusArea }
            }
        }

        // Add OD-Zones to cells and vice versa
        for (cell in grid) {
            // OD-Zone most buildings in cell belong to
            val odZone = cell.buildings.groupingBy { it.odZone }.eachCount().maxByOrNull { it.value }!!.key

            cell.odZone = odZone
            odZone?.aggLocs?.add(cell)
        }

        return dummyZones
    }

    /**
     * Calculate calibration factors from the od-matrix.
     * First-order factor changes the probability of a destination without taking the origin into account.
     *
     * P_calibrated(destination | activity) = P_base(destination | activity) * k_firstOrder(activity, destination)
     */
    private fun calcFirstOrderScaling(odZones: List<ODZone>) : Pair<ActivityType, Map<ODZone, Double>> {
        val activity = odZones.first().originActivity
        require(activity in listOf(ActivityType.HOME, ActivityType.WORK, ActivityType.SCHOOL))
            {"Scaling origins is only implemented for fixed locations"}

        val factors = mutableMapOf<ODZone, Double>()
        val omodProbs = calcOMODProbsAsMap(activity)
        val omodWeights = mutableMapOf<ODZone, Double>()
        val odWeights = mutableMapOf<ODZone, Double>()

        for (odZone in odZones) {
            // Calculate omod origin probability. For speed only on zone level.
            omodWeights[odZone] = odZone.aggLocs.sumOf { omodProbs[it]!! }
            // Calculate OD-Matrix origin probability.
            odWeights[odZone] = odZone.destinations.filter { it.first.inFocusArea }.sumOf { it.second }
        }

        val weightSumOMOD = omodWeights.values.sum()
        val weightSumOD = odWeights.values.sum()

        // Calibration failed!
        if ((weightSumOMOD <= 0) || (weightSumOD <= 0)){
            throw Exception("Calculation of first order calibration factors failed! " +
                            "Possible causes: OD-Matrix has negative values, " +
                            "OD-Matrix does not intersect focus area, ... \n" +
                            "If code was changed make sure that calcFirstOrderScaling is called after " +
                            "addODZoneInfo().")
        }

        for (odZone in odZones) {
            // Normalize
            val omodProb = omodWeights[odZone]!! / weightSumOMOD
            val odProb = odWeights[odZone]!! / weightSumOD
            factors[odZone] = odProb / omodProb
        }
       return Pair(activity, factors)
    }

    /**
     * Calculate calibration factors from the od-matrix.
     * Second-order factor changes the probability of a destination with taking the origin into account.
     *
     * P_calibrated(destination | activity, origin) =
     * P_base(destination | activity, origin) * k_firstOrder(activity, destination) * k_secondOrder(activity, destination, origin)
     */
    private fun calcSecondOrderScaling(odZones: List<ODZone>)
    : Pair<Pair<ActivityType, ActivityType> ,Map<Pair<ODZone, ODZone> , Double>> {
        val activities = Pair(odZones.first().originActivity, odZones.first().destinationActivity)

        // Check if OD has valid activities. Currently allowed: HOME->WORK
        require(activities == Pair(ActivityType.HOME, ActivityType.WORK)) {
            "Only OD-Matrices with Activities HOME->WORK are currently supported"
        }
        
        val factors = mutableMapOf<Pair<ODZone, ODZone>, Double>()
        val priorProbs = calcOMODProbsAsMap(activities.first)

        for (originOdZone in odZones) {
            val omodWeights = mutableMapOf<ODZone, Double>()
            val odWeights = mutableMapOf<ODZone, Double>()
            for ((destOdZone, transitions) in originOdZone.destinations) {
                // Calculate omod transition probability. For speed only on zone level.
                var omodWeight = 0.0
                for (origin in originOdZone.aggLocs) {
                    val prior = priorProbs[origin]!!
                    if (prior == 0.0) { continue }
                    omodWeight +=  prior * getWeights(origin, destOdZone.aggLocs, activities.second).sum()
                 }
                omodWeights[destOdZone] = omodWeight

                // Calculate OD-Matrix origin probability.
                odWeights[destOdZone] = if (destOdZone.inFocusArea || originOdZone.inFocusArea) transitions else 0.0
            }

            val weightSumOMOD = omodWeights.values.sum()
            val weightSumOD = odWeights.values.sum()

            // Transitions from origin are impossible. Leave unadjusted. Factor should never be used.
            if ((weightSumOMOD <= 0) || (weightSumOD <= 0)){
                for (destOdZone in originOdZone.destinations.map { it.first }) {
                    factors[Pair(originOdZone, destOdZone)] = 1.0
                }
            } else {
                for (destOdZone in originOdZone.destinations.map { it.first }) {
                    // Normalize
                    val gamgProb = omodWeights[destOdZone]!! / weightSumOMOD
                    val odProb = odWeights[destOdZone]!! / weightSumOD

                    factors[Pair(originOdZone, destOdZone)] = odProb / gamgProb
                }
            }
        }
        return Pair(activities, factors)
    }

    /**
     * Initialize population with fixed number of agents.
     * Assigns socio-demographic features, and home, work, and school locations.
     *
     * @param nFocus number of agents in focus areas
     * @return Population of agents
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun createAgents(nFocus: Int): List<MobiAgent> {
        println("Creating Population...")

        // Home distributions inside and outside of focus area
        val insideHWeights = getHomeWeightsRestricted(zones, true).toDoubleArray()
        val outsideHWeight = getHomeWeightsRestricted(zones, false).toDoubleArray()
        val insideCumDist = createCumDist(insideHWeights)
        val outsideCumDist = createCumDist(outsideHWeight)

        val totalNAgents = if (populateBufferArea) {
            // Check the rough proportions of in and out of focus area homes for emergency break
            val hWeights = getWeightsNoOrigin(zones, ActivityType.HOME)
            val inShare = insideHWeights.sum() / hWeights.sum()
            (nFocus / inShare).toInt()
        } else {
            nFocus
        }

        // Create agent until n life in the focus area
        val agents = ArrayList<MobiAgent>(totalNAgents)
        val agentFactory = AgentFactory()
        for (id in 0 until totalNAgents) {
            // Get home zone (might be cell or dummy is node)
            val inside = id < nFocus // Should agent live inside focus area
            val homeCumDist = if ( inside ) insideCumDist else outsideCumDist
            val homeZoneID = sampleCumDist(homeCumDist, rng)
            val homeZone = zones[homeZoneID]

            // Get home location
            val home = if (homeZone is Cell) { // Home is building
                val buildingsHomeDist = createCumDist(
                    getHomeWeightsRestricted(homeZone.buildings, inside).toDoubleArray()
                )
                homeZone.buildings[sampleCumDist(buildingsHomeDist, rng)]
            } else { // IS dummy location
                homeZone
            }

            // Add the agent to the population
            agents.add(agentFactory.createAgent(home, homeZone))
        }
        agents.shuffle(rng)
        return agents
    }

    /**
     * Initialize population based on a share of the existing population.
     * Assigns socio-demographic features, and home, work, and school locations.
     *
     * @param share Share of the population to simulate
     * @return Population of agents
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun createAgents(share: Double): List<MobiAgent> {
        println("Creating Population...")

        if (!censusAvailable) {
            throw Exception(
                "Agent population is supposed to be based on the population but no census file is provided." +
                "Consider adding a census file with --census or use --n_agents instead.")
        }

        // Determine populations of dummy zones
        val weightSumCells = getWeightsNoOrigin(grid, ActivityType.HOME).sum()
        val totalPopCells = grid.sumOf { it.population }
        val homeWeightsZones = getWeightsNoOrigin(zones, ActivityType.HOME)

        val dummyZones = mutableListOf<LocationOption>()
        val dummyZonePopulation = mutableListOf<Int>()
        for ((i, zone) in zones.withIndex()) {
            if (zone is DummyLocation) {
                val hWeight = homeWeightsZones[i]
                val synthPop = totalPopCells * (hWeight / weightSumCells)
                dummyZones.add(zone)
                dummyZonePopulation.add(synthPop.toInt())
            }
        }

        // Determine number of agents
        val totalAgentsBuildings = (buildings.sumOf { it.population } * share).toInt()
        val totalAgentsDummy = (dummyZonePopulation.sum() * share).toInt()
        val totalNAgents = totalAgentsBuildings + totalAgentsDummy

        // Create agents
        val agents = ArrayList<MobiAgent>(totalNAgents)
        val agentFactory = AgentFactory()
        // Living in buildings
        val buildingPopDistr = createCumDistWOR(buildings.map { it.population.toInt() }.toIntArray())
        for (id in 0 until totalAgentsBuildings) {
            val i = sampleCumDistWOR(buildingPopDistr, rng)
            val home = buildings[i]
            agents.add(agentFactory.createAgent(home, home.cell!!))
        }
        // Living at dummy location
        val dummyPopDistr = createCumDistWOR(dummyZonePopulation.toIntArray())
        for (id in 0 until totalAgentsDummy) {
            val i = sampleCumDistWOR(dummyPopDistr, rng)
            val home = dummyZones[i]
            agents.add(agentFactory.createAgent(home, home))
        }
        agents.shuffle(rng)
        return agents
    }

   /**
    * Creates agents by determining socio-demographic features as well as work and school locations.
    */
   private inner class AgentFactory {
        private val features: List<Triple<HomogeneousGrp, MobilityGrp, AgeGrp>>
        private val featureDistribution: DoubleArray
        private val workDistrCache = mutableMapOf<LocationOption, DoubleArray>()
        private val schoolDistrCache = mutableMapOf<LocationOption, DoubleArray>()
        private var nextID = 0

        init {
            val (f, fD) = getSocioDemographicFeatureDistr()
            features = f
            featureDistribution = fD
        }

       /**
        * Create agent with given home.
        *
        * @param home Home of the agent. Either a Building or a DummyLocation.
        * @param homeZone Routing cell of the home. Either a Cell or a DummyLocation
        * @return Agent
        */
       fun createAgent(home: LocationOption, homeZone: LocationOption) : MobiAgent {
           // Sociodemographic features
           val agentFeatures = sampleCumDist(featureDistribution, rng)
           val homogenousGroup = features[agentFeatures].first
           val mobilityGroup = features[agentFeatures].second
           val age = features[agentFeatures].third

           // Fixed locations
           val work = determineWorkLocation(homeZone)
           val school = determineSchoolLocation(homeZone)

           val agent = MobiAgent(nextID, homogenousGroup, mobilityGroup, age, home, work, school)
           nextID += 1
           return agent
       }

        /**
         * Create sociodemographic feature distribution form populationDef
         * @return Pair<Possible feature combinations, probability of the combination>
         */
        private fun getSocioDemographicFeatureDistr(
        ) : Pair<List<Triple<HomogeneousGrp, MobilityGrp, AgeGrp>>, DoubleArray> {
            val features = mutableListOf<Triple<HomogeneousGrp, MobilityGrp, AgeGrp>>()
            val jointProbability = mutableListOf<Double>()
            for ((hom, p_hom) in populationDef.homogenousGroup) {
                for ((mob, p_mob) in populationDef.mobilityGroup) {
                    for ((age, p_age) in populationDef.age) {
                        features.add(Triple(hom, mob, age))
                        jointProbability.add(p_hom*p_mob*p_age)
                    }
                }
            }
            return Pair(features, createCumDist(jointProbability.toDoubleArray()))
        }

       /**
        * Determine work location.
        * @param homeZone Routing cell of the home.
        * @return Workplace
        */
       private fun determineWorkLocation(homeZone: LocationOption) : LocationOption {
            // Get work zone (might be cell or dummy is node)
            val workZoneCumDist = workDistrCache.getOrPut(homeZone) {
                getDistr(homeZone, zones, ActivityType.WORK)
            }
            val workZone = zones[sampleCumDist(workZoneCumDist, rng)]

            // Get work location
            val work = if (workZone is Cell) {
                val workBuildingsCumDist = getDistrNoOrigin(workZone.buildings, ActivityType.WORK)
                workZone.buildings[sampleCumDist(workBuildingsCumDist, rng)]
            } else {
                workZone
            }
            return work
        }

       /**
        * Determine school location.
        * @param homeZone Routing cell of the home.
        * @return School location
        */
       private fun determineSchoolLocation(homeZone: LocationOption) : LocationOption {
           // Get school cell
           val schoolZoneCumDist = schoolDistrCache.getOrPut(homeZone) {
               getDistr(homeZone, zones, ActivityType.SCHOOL)
           }
           val schoolZone = zones[sampleCumDist(schoolZoneCumDist, rng)]

           // Get school location
           val school = if (schoolZone is Cell) {
               val schoolBuildingsCumDist = getDistrNoOrigin(schoolZone.buildings, ActivityType.SCHOOL)
               schoolZone.buildings[sampleCumDist(schoolBuildingsCumDist, rng)]
           } else {
               schoolZone
           }
           return school
       }
    }

    /**
     * Get the home weights of all destinations in the focus area and set the others to zero; or the other way around.
     *
     * @param destinations Destination options
     * @param inside True: Only the weights inside the focus area. False: Only those outside.
     * @return List of weights
     */
    private fun getHomeWeightsRestricted(destinations: List<LocationOption>, inside: Boolean) : List<Double> {
        val originalWeights = getWeightsNoOrigin(destinations, ActivityType.HOME)
        return if (inside) {
            originalWeights.mapIndexed { i, weight -> destinations[i].inFocusArea.toDouble() * weight }
        } else {
            originalWeights.mapIndexed { i, weight -> (!destinations[i].inFocusArea).toDouble() * weight }
        }
    }

    /**
     * Get the location of an activity with flexible location a given current location
     * @param location Coordinates of current location
     * @param type Activity type
     * @return Destination
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun findFlexibleLoc(location: LocationOption, type: ActivityType): LocationOption {
        // Home, Work, School are not flexible
        require(type != ActivityType.HOME)
        require(type != ActivityType.WORK)
        require(type != ActivityType.SCHOOL)

        val zone = if (location is Building) {
            location.cell!!
        } else {
            location
        }

        // Get cell
        val flexDist = if (type == ActivityType.SHOPPING) {
            getDistr(zone, zones, ActivityType.SHOPPING)
        } else {
            getDistr(zone, zones, ActivityType.OTHER)
        }
        val flexZone = zones[sampleCumDist(flexDist, rng)]

        // Get location
        return if (flexZone is Cell) {
            val flexBuildingCumDist = if (type == ActivityType.SHOPPING) {
                getDistrNoOrigin(flexZone.buildings, ActivityType.SHOPPING)
            } else {
                getDistrNoOrigin(flexZone.buildings, ActivityType.OTHER)
            }
            flexZone.buildings[sampleCumDist(flexBuildingCumDist, rng)]
        } else {
            flexZone
        }
    }

    /**
     * Determine the activity chain of an agent.
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from The yesterday's last activity
     * @return List of activities conducted by the agent today
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getActivityChain(agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED,
                         from: ActivityType = ActivityType.HOME) : List<ActivityType> {
        val chainData = activityDataStore.getChain(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from)
        val i = sampleCumDist(chainData.distr, rng)
        return chainData.chains[i]
    }

    /**
     * Get the stay times at each activity.
     *
     * @param activityChain The activities the agent will undertake that day
     * @param agent The agent
     * @param weekday The weekday
     * @return Stay times at each activity
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getStayTimes(activityChain: List<ActivityType>, agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED
        ) : List<Double?> {
        return if (activityChain.size == 1) {
            // Stay at one location the entire day
            listOf(null)
        } else {
            // Sample stay times from gaussian mixture
            val mixture = activityDataStore.getMixture(
                weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, activityChain
            )
            val i = sampleCumDist(mixture.distr, rng)
            val stayTimes = sampleNDGaussian(mixture.means[i], mixture.covariances[i], rng).toList()
            // Handle negative values. Last stay is always until the end of the day, marked by null
            stayTimes.map { if (it < 0 ) 0.0 else it } + null
        }
    }

    /**
     * Get the activity locations for the given agent.
     *
     * @param agent The agent
     * @param activityChain The activities the agent will undertake that day
     * @param start Location the day starts at
     * @return Locations of each activity
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getLocations(agent: MobiAgent, activityChain: List<ActivityType>,
                     start: LocationOption
    ) : List<LocationOption> {
        val locations = mutableListOf<LocationOption>()
        activityChain.forEachIndexed { i, activity ->
            if (i == 0){
                locations.add(start)
            } else {
                locations.add(
                    when (activity) {
                        ActivityType.HOME -> agent.home
                        ActivityType.WORK -> agent.work
                        ActivityType.SCHOOL -> agent.school
                        else -> findFlexibleLoc(locations[i-1], activity)
                    }
                )
            }
        }
        return locations
    }

    /**
     * Get the activity schedule (Activity types, locations, and stay times)
     * Defines the start location based on the last activity yesterday.
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from Last activity yesterday
     * @return Activity schedule
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getActivitySchedule(agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED,
                            from: ActivityType = ActivityType.HOME
    ): List<Activity> {
        val location = when(from) {
            ActivityType.HOME -> agent.home
            ActivityType.WORK -> agent.work
            ActivityType.SCHOOL -> agent.school
            else -> throw Exception("Start must be either Home, Work, School, or coordinates must be given. Agent: ${agent.id}")
        }
        return getActivitySchedule(agent, weekday, from, location)
    }

    /**
     * Get the activity schedule (Activity types, locations, and stay times)
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from Last activity yesterday
     * @param start Location the day starts at
     * @return Activity schedule
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getActivitySchedule(agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED, from: ActivityType = ActivityType.HOME,
                           start: LocationOption
    ): List<Activity> {
        val activityChain = getActivityChain(agent, weekday, from)
        val stayTimes = getStayTimes(activityChain, agent, weekday)
        val locations = getLocations(agent, activityChain, start)
        return List(activityChain.size) { i ->
            Activity(activityChain[i], stayTimes[i], locations[i], locations[i].latlonCoord.x, locations[i].latlonCoord.y)
        }
    }

    /**
     * Generate the mobility demand of the area.
     *
     * @param n_agents Number of agents
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return List of agents each with an activity schedules for every simulated day
     */
    fun run(n_agents: Int, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        val agents = createAgents(n_agents)
        return run(agents, start_wd, n_days)
    }

    /**
     * Generate the mobility demand of the area.
     *
     * @param shareOfPop Share of population to simulate
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return List of agents each with an activity schedules for every simulated day
     */
    fun run(shareOfPop: Double, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        val agents = createAgents(shareOfPop)
        return run(agents, start_wd, n_days)
    }

    /**
     * Generate the mobility demand of the area.
     *
     * @param agents Agents
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return List of agents each with an activity schedules for every simulated day
     */
    fun run(agents: List<MobiAgent>, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        var weekday = start_wd

        var jobsDone = 0
        val totalJobs = (agents.size * n_days).toDouble()
        for (i in 0 until n_days) {
            for (agent in agents) {
                print( "Running model: ${ProgressBar.show( jobsDone / totalJobs )}\r" )

                val activities = if (agent.mobilityDemand.isEmpty()) {
                    getActivitySchedule(agent, weekday)
                } else {
                    val lastActivity = agent.mobilityDemand.last().activities.last()
                    getActivitySchedule(
                        agent,
                        weekday,
                        from = lastActivity.type,
                        start = lastActivity.location
                    )
                }
                agent.mobilityDemand.add( Diary(i, weekday, activities) )
                jobsDone += 1
            }
            weekday = weekday.next()
        }
        println("Running model: " + ProgressBar.done())
        routingCache.toOOMCache() // Save routing cache
        return agents
    }

    /**
     * Calculate probability that an activity of type x happens at certain location for all locations.
     * Used to compare OMODs od probabilities with that of the od-file.
     * Possible activity types are: HOME and WORK
     *
     * P(Location | HOME) = Distribution used for Home location assignment
     * P(Location | WORK) = sum( P(Location | WORK, Origin=x) * P(x | HOME) ) over all locations x
     *
     * @param activityType The activity type x
     * @return Probability that an activity of type x happens at certain location for all locations
     */
    private fun calcOMODProbs(activityType: ActivityType) : DoubleArray {
        require(activityType in listOf(ActivityType.HOME, ActivityType.WORK))
        {"Flexible locations are not  yet supported for k-Factor calibration!"}
        // Home distribution
        val homeWeights = getWeightsNoOrigin(zones, ActivityType.HOME)
        val totalHomeWeight = homeWeights.sum()
        val homeProbs = homeWeights.map { it / totalHomeWeight }.toDoubleArray()
        if (activityType == ActivityType.HOME) {
            return homeProbs
        }

        // Work distribution
        val workProbs = DoubleArray(zones.size) { 0.0 }
        for (zone in zones) {
            val workWeights = getWeights(zone, zones, ActivityType.WORK)
            val totalWorkWeight = workWeights.sum()
            for (i in zones.indices) {
                workProbs[i] += homeProbs[i] * workWeights[i] / totalWorkWeight
            }
        }
        return workProbs
    }


    /**
     * Wrapper for calcOMODProbs that returns a map instead of an array.
     */
    private fun calcOMODProbsAsMap(activityType: ActivityType) : Map<LocationOption, Double> {
        val probs = calcOMODProbs(activityType)
        val map = mutableMapOf<LocationOption, Double>()
        for (i in zones.indices) {
            map[zones[i]] = probs[i]
        }
        return map
    }

    /**
     * Determine the probability that a location is a destination given an origin and activity type
     * for all possible destinations.
     *
     * @param origin Origin of the trip
     * @param destinations Possible destinations
     * @param activityType Activity type conducted at the destination.
     * @return Cumulative distribution of the destination probabilities
     */
    private fun getDistr(origin: LocationOption, destinations: List<LocationOption>,
                 activityType: ActivityType
    ) : DoubleArray {
        val weights = getWeights(origin, destinations, activityType)
        return createCumDist(weights.toDoubleArray())
    }

    /**
     * Determine the probability that a location is a destination given an activity type but no origin
     * for all possible destinations.
     *
     * @param destinations Possible destinations
     * @param activityType Activity type conducted at the destination.
     * @return Cumulative distribution of the destination probabilities
     */
    private fun getDistrNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : DoubleArray {
        val weights = getWeightsNoOrigin(destinations, activityType)
        return createCumDist(weights.toDoubleArray())
    }

    /**
     * Determine the probabilistic weight that a location is a destination given an origin and activity type
     * for all possible destinations.
     *
     * @param destinations Possible destinations
     * @param activityType Activity type conducted at the destination.
     * @return Probabilistic weights
     */
    private fun getWeights(origin: LocationOption, destinations: List<LocationOption>,
                   activityType: ActivityType
    ): List<Double> {
        require(activityType != ActivityType.HOME) { "For HOME activities call getWeightsNoOrigin()!" }

        // Don't leave dummy location except OD-Matrix defines it
        if (origin is DummyLocation) {
            if (activityType !in origin.transferActivities) {
                return destinations.map { if (origin == it) 1.0 else 0.0 }
            }
        }

        val distances = routingCache.getDistances(origin, destinations)
        val weightFunction = locChoiceWeightFuns[activityType]!!

        val weights = destinations.mapIndexed { i, destination ->
            when(destination) {
                is DummyLocation -> {
                    if (activityType !in destination.transferActivities) {
                        0.0
                    } else {
                        1.0
                    }
                }
                is RealLocation -> {
                    val distance = distances[i].toDouble()
                    weightFunction.calcFor(destination, distance)
                }
            }
        }

        return if (activityType == ActivityType.WORK) {
           destinations.mapIndexed { i, destination ->
               val foFactor = firstOrderCFactors[activityType]?.get(destination.odZone) ?: 1.0
               val soFactor = secondOrderCFactors[Pair(ActivityType.HOME, activityType)]
                   ?.get(Pair(origin.odZone, destination.odZone)) ?: 1.0
               foFactor * soFactor * weights[i]
           }
        } else {
           weights
        }
    }

    /**
     * Determine the probabilistic weight that a location is a destination given an activity type but no origin
     * for all possible destinations.
     *
     * @param destinations Possible destinations
     * @param activityType Activity type conducted at the destination.
     * @return Probabilistic weights
     */
    private fun getWeightsNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : List<Double> {
        val weightFunction = locChoiceWeightFuns[activityType]!!

        val weights = destinations.map { destination ->
            when (destination) {
                is DummyLocation -> {
                    if (activityType !in destination.transferActivities) {
                        0.0
                    } else {
                        1.0
                    }
                }
                is RealLocation -> {
                    weightFunction.calcForNoOrigin(destination)
                }
            }
        }

        return when(activityType) {
            ActivityType.HOME -> {
                destinations.mapIndexed { i, destination ->
                    val foFactor = firstOrderCFactors[activityType]?.get(destination.odZone) ?: 1.0
                    foFactor * weights[i]
                }
            }
            ActivityType.WORK -> destinations.mapIndexed { i, destination ->
                val foFactor = firstOrderCFactors[activityType]?.get(destination.odZone) ?: 1.0
                foFactor * weights[i]
            }
            else -> weights
        }
    }
}
