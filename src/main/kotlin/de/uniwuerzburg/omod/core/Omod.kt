package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
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
import org.locationtech.jts.io.WKTReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


/**
 * Open-Street-Maps MObility Demand generator (OMOD)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */

@Suppress("MemberVisibilityCanBePrivate")
class Omod(
    areaWKT: String,
    osmFile: File,
    mode: RoutingMode = RoutingMode.BEELINE,
    cache: Boolean = true,
    cacheDir: Path = Paths.get("omod_cache/"),
    odFile: File? = null,
    gridResolution: Double = 500.0,
    seed: Long? = null,
    bufferRadius: Double = 0.0,
    censusFile: File? = null,
) {
    val kdTree: KdTree
    val buildings: List<Building>
    private val grid: List<Cell>
    private val zones: List<AggregateLocation> // Grid + DummyLocations for commuting locations
    private val firstOrderCFactors = mutableMapOf<ActivityType, Map<ODZone, Double>>()
    private val secondOrderCFactors = mutableMapOf<Pair<ActivityType, ActivityType>, Map<Pair<ODZone, ODZone>, Double>>()
    private val populationDef: PopulationDef
    private val activityDataMap: ActivityDataMap
    private val locChoiceWeightFuns: Map<ActivityType, LocationChoiceDCWeightFun>
    private val rng: Random = if (seed != null) Random(seed) else Random()
    private val hopper: GraphHopper?
    private val routingCache: RoutingCache
    private val geometryFactory: GeometryFactory = GeometryFactory()
    private val transformer: CRSTransformer

    init {
        // Get population distribution
        val popTxt = Omod::class.java.classLoader.getResource("Population.json")!!.readText(Charsets.UTF_8)
        populationDef = Json.decodeFromString(popTxt)

        // Get activity chain data
        val actTxt = Omod::class.java.classLoader.getResource("ActivityGroups.json")!!.readText(Charsets.UTF_8)
        val activityGroups: List<ActivityGroup> = Json.decodeFromString(actTxt)
        activityDataMap = ActivityDataMap(activityGroups)

        // Get distance distributions
        val distrTxt = Omod::class.java.classLoader.getResource("LocChoiceWeightFuns.json")!!.readText(Charsets.UTF_8)
        locChoiceWeightFuns = Json.decodeFromString<List<LocationChoiceDCWeightFun>>(distrTxt)
            .associateBy { it.destActivity }

        // Get spatial data
        transformer = CRSTransformer()
        buildings = getBuildings(areaWKT, geometryFactory, transformer, osmFile, cacheDir, bufferRadius,
                                     censusFile, cache)

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
        routingCache = RoutingCache(mode, hopper)
        if (mode == RoutingMode.GRAPHHOPPER) {
            routingCache.load(grid, cacheDir)
        }

        // Calibration
        if (odFile != null) {
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
        fun defaultFactory(areaWKT: String, osmFile: File): Omod {
            return Omod(areaWKT, osmFile)
        }
    }

    /**
     * Get the buildings.
     */
    fun getBuildings(areaWKT: String, geometryFactory: GeometryFactory, transformer: CRSTransformer,
                     osmFile: File, cacheDir: Path, bufferRadius: Double = 0.0, censusFile: File?,
                     cache: Boolean) : List<Building> {
        val area = WKTReader(geometryFactory).read( areaWKT )
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
        val hopper = GraphHopper()
        hopper.osmFile = osmLoc
        hopper.graphHopperLocation = cacheLoc
        hopper.setProfiles(Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false))
        hopper.chPreparationHandler.setCHProfiles(CHProfile("car"))
        hopper.importOrLoad()
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
                    homeWeight = (ActivityType.HOME in activities).toDouble(),
                    workWeight = (ActivityType.WORK in activities).toDouble(),
                    schoolWeight = (ActivityType.SCHOOL in activities).toDouble(),
                    shoppingWeight = (ActivityType.SHOPPING in activities).toDouble(),
                    otherWeight = (ActivityType.OTHER in activities).toDouble(),
                    odZone = odZone
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
        require(activities in setOf(Pair(ActivityType.HOME, ActivityType.WORK))) {
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
     * @param n number of agents in focus area
     * @param inputPopDef sociodemographic distribution of the agents. If null the distributions in Population.json are used.
     */
    fun createAgents(n: Int, inputPopDef: Map<String, Map<String, Double>>? = null): List<MobiAgent> {
        val agents = mutableListOf<MobiAgent>()

        // Get sociodemographic features
        val usedPopDef = if (inputPopDef == null) {
            populationDef
        } else {
            PopulationDef(inputPopDef)
        }
        val features = mutableListOf<Triple<String, String, String>>()
        val jointProbability = mutableListOf<Double>()
        for ((hom, p_hom) in usedPopDef.homogenousGroup) {
            for ((mob, p_mob) in usedPopDef.mobilityGroup) {
                for ((age, p_age) in usedPopDef.age) {
                    features.add(Triple(hom, mob, age))
                    jointProbability.add(p_hom*p_mob*p_age)
                }
            }
        }
        val featureDistribution =  createCumDist(jointProbability.toDoubleArray())

        // Assign home and work
        val homCumDist = getDistrNoOrigin(zones, ActivityType.HOME)

        // Generate population
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val schoolDistCache = mutableMapOf<Int, DoubleArray>()

        // Check the rough proportions of in and out of focus area homes for emergency break
        val hWeights = getWeightsNoOrigin(zones, ActivityType.HOME)
        val inShare = hWeights.filterIndexed { i, _ -> zones[i].inFocusArea }.sum() / hWeights.sum()

        require(inShare > 0) {"Must be possible to life in focus zone!"}

        // Create agent until n life in the focus area
        var id = 0
        var agentsInFocusArea = 0
        while (agentsInFocusArea < n) {
            // Sociodemographic features
            val agentFeatures = sampleCumDist(featureDistribution, rng)
            val homogenousGroup = features[agentFeatures].first
            val mobilityGroup = features[agentFeatures].second
            val age = features[agentFeatures].third

            // Get home zone (might be cell or dummy is node)
            val homeZoneID = sampleCumDist(homCumDist, rng)
            val homeZone = zones[homeZoneID]

            // Get home location
            val home = if (homeZone is Cell) {
                // IS building
                val buildingsHomeDist = getDistrNoOrigin(homeZone.buildings, ActivityType.HOME)
                homeZone.buildings[sampleCumDist(buildingsHomeDist, rng)]
            } else {
                // IS dummy location
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

            // Counter
            id += 1
            if (home.inFocusArea) { agentsInFocusArea += 1 }
            // Catch infinite loops.
            // If number of agents in focus area is 10 standard deviations
            // smaller than the expected value something might have gone wrong.
            require(id * inShare - 10 * sqrt(id * inShare * (1 - inShare)) < 100 + agentsInFocusArea )
                {"Loop seems infinite!"}
        }
        return agents
    }

    /**
     * Get the location of an activity with flexible location with a given current location
     * @param location Coordinates of current location
     * @param type Activity type
     */
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
    fun getActivityChain(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME) : List<ActivityType> {
        val data = activityDataMap.get(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from)
        val i = sampleCumDist(data.distr, rng)
        return data.chains[i]
    }

    /**
     * Get the stay times given an activity chain
     */
    fun getStayTimes(activityChain: List<ActivityType>, agent: MobiAgent, weekday: String = "undefined",
                     from: ActivityType = ActivityType.HOME
    ) : List<Double?> {
        return if (activityChain.size == 1) {
            // Stay at one location the entire day
            listOf(null)
        } else {
            // Sample stay times from gaussian mixture
            val data = activityDataMap.get(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from,
                                           givenChain = activityChain)
            val mixture = data.mixtures[activityChain]!! // non-null is ensured in get()
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
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined",
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
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME,
                           start: LocationOption
    ): List<Activity> {
        val activityChain = getActivityChain(agent, weekday, from)
        val stayTimes = getStayTimes(activityChain, agent, weekday, from)
        val locations = getLocations(agent, activityChain, start)
        return List(activityChain.size) { i ->
            Activity(activityChain[i], stayTimes[i], locations[i], locations[i].latlonCoord.x, locations[i].latlonCoord.y)
        }
    }

    /**
     * Determine the demand of the area for n agents at one day.
     * Optionally safe to json.
     */
    fun run(n_agents: Int, start_wd: String = "mo", n_days: Int = 1) : List<MobiAgent> {
        val agents = createAgents(n_agents)
        val offset = weekdays.indexOf(start_wd)

        for (i in 0..n_days) {
            val weekday = if (start_wd =="undefined") {
                "undefined"
            } else {
                weekdays[(i + offset) % weekdays.size]
            }
            for (agent in agents) {
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
            }
        }
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
        val activity = when(activityType) {
            ActivityType.HOME -> throw NotImplementedError("For HOME activities call getWeightsNoOrigin()!")
            ActivityType.WORK -> ActivityType.WORK
            ActivityType.SCHOOL -> ActivityType.SCHOOL
            ActivityType.SHOPPING -> ActivityType.SHOPPING
            else -> ActivityType.OTHER
        }

        // Flexible activities don't leave dummy location except OD-Matrix defines it
        if (origin is DummyLocation) {
            if (((activity == ActivityType.SHOPPING) && (origin.shoppingWeight == 0.0)) ||
                ((activity == ActivityType.OTHER)    && (origin.otherWeight == 0.0))) {
                return destinations.map { if (origin == it)  1.0 else 0.0 }
            }
        }

        val distances = calcDistances(origin, destinations)
        val weightFunction = locChoiceWeightFuns[activity]!!

       return if (activity == ActivityType.WORK) {
           destinations.mapIndexed { i, destination ->
               val foFactor = firstOrderCFactors[activityType]?.get(destination.odZone) ?: 1.0
               val soFactor = secondOrderCFactors[Pair(ActivityType.HOME, activityType)]
                   ?.get(Pair(origin.odZone, destination.odZone)) ?: 1.0
               val distance = distances[i].toDouble()
               foFactor * soFactor * weightFunction.calcFor(destination, distance)
           }
        } else {
           destinations.mapIndexed { i, destination ->
               val distance = distances[i].toDouble()
               weightFunction.calcFor(destination, distance)
           }
        }
    }

    fun getWeightsNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : List<Double> {
        return when(activityType) {
            ActivityType.HOME -> {
                destinations.map { destination ->
                    val foFactor = firstOrderCFactors[activityType]?.get(destination.odZone) ?: 1.0
                    foFactor * destination.getPriorWeightFor(activityType)
                }
            }
            ActivityType.WORK -> destinations.map { destination ->
                val foFactor = firstOrderCFactors[activityType]?.get(destination.odZone) ?: 1.0
                foFactor * destination.getPriorWeightFor(activityType)
            }
            else -> destinations.map { it.getPriorWeightFor(activityType) }
        }
    }

    fun calcDistances(origin: LocationOption, destinations: List<LocationOption>) : FloatArray {
        return routingCache.getDistances(origin, destinations)
    }
}
