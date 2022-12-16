package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.jackson.Jackson
import com.graphhopper.routing.weighting.custom.CustomProfile
import com.graphhopper.util.CustomModel
import de.uniwuerzburg.omod.io.GeoJsonFeatureCollection
import de.uniwuerzburg.omod.io.buildArea
import de.uniwuerzburg.omod.io.json
import de.uniwuerzburg.omod.routing.RoutingCache
import de.uniwuerzburg.omod.routing.RoutingMode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import org.locationtech.jts.index.kdtree.KdTree
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


/**
 * Open-Street-Maps MObility Demand generator (OMOD)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */

@Suppress("MemberVisibilityCanBePrivate")
class Omod(
    areaFile: File,
    osmFile: File,
    mode: RoutingMode = RoutingMode.BEELINE,
    cache: Boolean = true,
    cacheDir: Path = Paths.get("omod_cache/"),
    odFile: File? = null,
    gridResolution: Double = 500.0,
    seed: Long? = null,
    bufferRadius: Double = 0.0,
    censusFile: File? = null,
    private val populateBufferArea: Boolean = true,
    distanceCacheSize: Int = 20_000
) {
    val kdTree: KdTree
    val buildings: List<Building>
    private val grid: List<Cell>
    private val zones: List<LocationOption> // Grid + DummyLocations for commuting locations
    private val firstOrderCFactors = mutableMapOf<ActivityType, Map<ODZone, Double>>()
    private val secondOrderCFactors = mutableMapOf<Pair<ActivityType, ActivityType>, Map<Pair<ODZone, ODZone>, Double>>()
    private val populationDef: PopulationDef
    private val activityDataStore: ActivityDataStore
    private val locChoiceWeightFuns: Map<ActivityType, LocationChoiceDCWeightFun>
    private val rng: Random = if (seed != null) Random(seed) else Random()
    private val hopper: GraphHopper?
    private val routingCache: RoutingCache
    private val geometryFactory: GeometryFactory = GeometryFactory()
    private val transformer: CRSTransformer
    private val logger = LoggerFactory.getLogger(Omod::class.java)

    init {
        // Get population distribution
        val popTxt = Omod::class.java.classLoader.getResource("Population.json")!!.readText(Charsets.UTF_8)
        populationDef = Json.decodeFromString(popTxt)

        // Get activity chain data
        val actTxt = Omod::class.java.classLoader.getResource("ActivityGroups.json")!!.readText(Charsets.UTF_8)
        val activityGroups: List<ActivityGroup> = Json.decodeFromString(actTxt)
        activityDataStore = ActivityDataStore(activityGroups)

        // Get distance distributions
        val distrTxt = Omod::class.java.classLoader.getResource("LocChoiceWeightFuns.json")!!.readText(Charsets.UTF_8)
        val mutLocChoiceFuns: MutableMap<ActivityType, LocationChoiceDCWeightFun> = Json.decodeFromString(distrTxt)
        if (censusFile != null) {
            mutLocChoiceFuns[ActivityType.HOME] = ByPopulation
        }
        mutLocChoiceFuns[ActivityType.BUSINESS] = mutLocChoiceFuns[ActivityType.OTHER]!!
        locChoiceWeightFuns = mutLocChoiceFuns.toMap()

        // Get spatial data
        transformer = CRSTransformer
        buildings = getBuildings(areaFile, geometryFactory, transformer, osmFile, cacheDir,
                                 bufferRadius, censusFile, cache)

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building)
        }

        // Create grid (used for speed up)
        grid = makeGrid(gridResolution)
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
         * Run quick and easy without any additional information
         */
        @Suppress("unused")
        fun defaultFactory(areaFile: File, osmFile: File): Omod {
            return Omod(areaFile, osmFile)
        }
    }

    /**
     * Get the buildings.
     */
    fun getBuildings(areaFile: File, geometryFactory: GeometryFactory, transformer: CRSTransformer,
                     osmFile: File, cacheDir: Path, bufferRadius: Double = 0.0, censusFile: File?,
                     cache: Boolean) : List<Building> {
        // Read area geojson
        val areaColl: GeoJsonFeatureCollection = json.decodeFromString(areaFile.readText(Charsets.UTF_8))
        val area = geometryFactory.createGeometryCollection(
            areaColl.features.map { it.geometry.toJTS(geometryFactory) }.toTypedArray()
        ).union()

        // Is cached?
        val bound = area.envelopeInternal
        val cachePath = Paths.get(cacheDir.toString(),
            "AreaBounds${listOf(bound.minX, bound.maxX, bound.minY, bound.maxY)
                .toString().replace(" ", "")}" +
                    "Buffer${bufferRadius}" +
                    "Census${censusFile != null}" +
                    ".geojson"
        )

        // Check cache
        val collection: GeoJsonFeatureCollection
        if (cache and cachePath.toFile().exists()) {
            collection = json.decodeFromString(cachePath.toFile().readText(Charsets.UTF_8))
        } else {
            // Load data from geojson files and PostgreSQL database with OSM data
            collection = buildArea(
                area = area,
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
     * Group buildings with a regular grid for faster sampling.
     */
    fun makeGrid(gridResolution: Double) : List<Cell> {
        val grid = mutableListOf<Cell>()
        val xMin = buildings.minOfOrNull { it.coord.x } ?:0.0
        val yMin = buildings.minOfOrNull { it.coord.y } ?:0.0
        val xMax = buildings.maxOfOrNull { it.coord.x } ?:0.0
        val yMax = buildings.maxOfOrNull { it.coord.y } ?:0.0

        var id = 0
        for (x in semiOpenDoubleRange(xMin, xMax, gridResolution)) {
            for (y in semiOpenDoubleRange(yMin, yMax, gridResolution)) {
                val envelope = Envelope(x, x+gridResolution, y, y+gridResolution)
                val cellBuildings = kdTree.query(envelope).map { ((it as KdNode).data as Building) }
                if (cellBuildings.isEmpty()) continue

                // Centroid of all contained buildings
                val featureCentroid = geometryFactory.createMultiPoint(
                    cellBuildings.map { it.point }.toTypedArray()
                ).centroid.coordinate

                val latlonCoord = transformer.toLatLon( geometryFactory.createPoint(featureCentroid) ).coordinate

                val cell = Cell(
                    id = id,
                    coord = featureCentroid,
                    latlonCoord = latlonCoord,
                    envelope = envelope,
                    buildings = cellBuildings,
                )

                grid.add(cell)
                id += 1
            }
        }
        return grid.toList()
    }

    private fun createGraphHopper(osmLoc: String, cacheLoc: String) : GraphHopper {
        logger.info("Initializing GraphHopper... (If the osm.pbf is large this can take some time. " +
                    "Change routing_mode to BEELINE for fast results.)")
        val hopper = GraphHopper()
        hopper.osmFile = osmLoc
        hopper.graphHopperLocation = cacheLoc

        // Custom Profile
        val cp = CustomProfile("custom_car")
        val configURL = Omod::class.java.classLoader.getResource("ghConfig.json")!!
        val cm: CustomModel = Jackson.newObjectMapper().readValue(configURL, CustomModel::class.java)
        cp.customModel = cm

        hopper.setProfiles(cp)
        hopper.chPreparationHandler.setCHProfiles(CHProfile("custom_car"))
        hopper.importOrLoad()
        logger.info("GraphHopper initialized!")
        return hopper
    }

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
    
    // Aka k factors
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
     * Initialize population by assigning home and work locations
     * @param nFocus number of agents in focus areas
     */
    fun createAgents(nFocus: Int): List<MobiAgent> {
        val agents = mutableListOf<MobiAgent>()

        // Get sociodemographic features
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
        val featureDistribution = createCumDist(jointProbability.toDoubleArray())

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
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val schoolDistCache = mutableMapOf<Int, DoubleArray>()
        for (id in 0 until totalNAgents) {
            // Sociodemographic features
            val agentFeatures = sampleCumDist(featureDistribution, rng)
            val homogenousGroup = features[agentFeatures].first
            val mobilityGroup = features[agentFeatures].second
            val age = features[agentFeatures].third

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

            // Get work zone (might be cell or dummy is node)
            val workZoneCumDist = workDistCache.getOrPut(homeZoneID) {
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

            // Get school cell
            val schoolZoneCumDist = schoolDistCache.getOrPut(homeZoneID) {
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

            // Add the agent to the population
            agents.add(MobiAgent(id, homogenousGroup, mobilityGroup, age, home, work, school))
        }
        return agents
    }

    private fun getHomeWeightsRestricted(destinations: List<LocationOption>, inside: Boolean) : List<Double> {
        val originalWeights = getWeightsNoOrigin(destinations, ActivityType.HOME)
        return if (inside) {
            originalWeights.mapIndexed { i, weight -> destinations[i].inFocusArea.toDouble() * weight }
        } else {
            originalWeights.mapIndexed { i, weight -> (!destinations[i].inFocusArea).toDouble() * weight }
        }
    }

    /**
     * Get the location of an activity with flexible location with a given current location
     * @param location Coordinates of current location
     * @param type Activity type     */
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
     * Get the activity chain
     */
    fun getActivityChain(agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED,
                         from: ActivityType = ActivityType.HOME) : List<ActivityType> {
        val chainData = activityDataStore.getChain(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from)
        val i = sampleCumDist(chainData.distr, rng)
        return chainData.chains[i]
    }

    /**
     * Get the stay times given an activity chain
     */
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
     * @param agent The agent
     * @param activityChain The activities the agent will undertake that day
     * @param start The location the day starts at
     */
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
     * Get the mobility profile for the given agent.
     */
    fun getMobilityProfile(agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED,
                           from: ActivityType = ActivityType.HOME
    ): List<Activity> {
        val location = when(from) {
            ActivityType.HOME -> agent.home
            ActivityType.WORK -> agent.work
            ActivityType.SCHOOL -> agent.school
            else -> throw Exception("Start must be either Home, Work, School, or coordinates must be given. Agent: ${agent.id}")
        }
        return getMobilityProfile(agent, weekday, from, location)
    }
    fun getMobilityProfile(agent: MobiAgent, weekday: Weekday = Weekday.UNDEFINED, from: ActivityType = ActivityType.HOME,
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
     * Determine the demand of the area for n agents at one day.
     * Optionally safe to json.
     */
    fun run(n_agents: Int, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        val agents = createAgents(n_agents)
        var weekday = start_wd

        var jobsDone = 0
        val totalJobs = (agents.size * n_days).toDouble()
        for (i in 0 until n_days) {
            for (agent in agents) {
                print( "Running model: ${ProgressBar.show( jobsDone / totalJobs )}\r" )

                if (agent.profile == null) {
                    agent.profile = getMobilityProfile(agent, weekday)
                } else {
                    val lastActivity = agent.profile!!.last()
                    agent.profile = getMobilityProfile(
                        agent,
                        weekday,
                        from = lastActivity.type,
                        start = lastActivity.location
                    )
                }
                jobsDone += 1
            }
            weekday = weekday.next()
        }
        println("Running model: " + ProgressBar.done())
        routingCache.toOOMCache() // Save routing cache
        return agents
    }

    fun calcOMODProbsAsMap(activityType: ActivityType) : Map<LocationOption, Double> {
        val probs = calcOMODProbs(activityType)
        val map = mutableMapOf<LocationOption, Double>()
        for (i in zones.indices) {
            map[zones[i]] = probs[i]
        }
        return map
    }

    fun calcOMODProbs(activityType: ActivityType) : DoubleArray {
        require(activityType in listOf(ActivityType.HOME, ActivityType.WORK))
        {"Flexible locations are not  yet supported for k-Factor calibration!"}
        // Home distribution
        val homeWeights = getWeightsNoOrigin(zones, ActivityType.HOME)
        val totalHomeWeight = homeWeights.sum()
        val homeProbs = homeWeights.map { it / totalHomeWeight }.toDoubleArray()
        if (activityType == ActivityType.HOME) { return homeProbs }
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
     * Determine probabilities for the activities
     */
    fun getDistr(origin: LocationOption, destinations: List<LocationOption>,
                 activityType: ActivityType
    ) : DoubleArray {
        val weights = getWeights(origin, destinations, activityType)
        return createCumDist(weights.toDoubleArray())
    }
    fun getDistrNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : DoubleArray {
        val weights = getWeightsNoOrigin(destinations, activityType)
        return createCumDist(weights.toDoubleArray())
    }

    fun getWeights(origin: LocationOption, destinations: List<LocationOption>,
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
                        1.0
                    } else {
                        0.0
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

    fun getWeightsNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : List<Double> {
        val weightFunction = locChoiceWeightFuns[activityType]!!

        val weights = destinations.map { destination ->
            when (destination) {
                is DummyLocation -> {
                    if (activityType !in destination.transferActivities) {
                        1.0
                    } else {
                        0.0
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
