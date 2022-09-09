package de.uniwuerzburg

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import org.locationtech.jts.index.kdtree.KdTree
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

val weekdays = listOf("mo", "tu", "we", "th", "fr", "sa", "so")

/**
 * Open-Street-Maps MObility Demand generator (OMOD)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Omod(val buildings: List<Building>, odFile: File?, gridResolution: Double?, seed: Long?,
           private val geometryFactory: GeometryFactory = GeometryFactory()) {
    val kdTree: KdTree
    private val grid: List<Cell>
    private val zones: List<LocationOption> // Grid + DummyLocations for commuting locations
    private val calibrationMatrix: MutableMap<ActivityType, Map<Pair<String?, String>, Double>?>
    private val populationDef: PopulationDef
    private val activityDataMap: ActivityDataMap
    private val distanceDists: DistanceDistributions
    private val rng: Random = if (seed != null) Random(seed) else Random()
    private val hopper: GraphHopper
    private val mode: RoutingMode = RoutingMode.BEELINE
    private val routingTable: Map<LocationOption, Map<LocationOption, Double>>

    init {
        // Get population distribution
        val popTxt = Omod::class.java.classLoader.getResource("Population.json")!!.readText(Charsets.UTF_8)
        populationDef = Json.decodeFromString(popTxt)

        // Get activity chain data
        val actTxt = Omod::class.java.classLoader.getResource("ActivityGroups.json")!!.readText(Charsets.UTF_8)
        val activityGroups: List<ActivityGroup> = Json.decodeFromString(actTxt)
        activityDataMap = ActivityDataMap(activityGroups)

        // Get distance distributions
        val distrTxt = Omod::class.java.classLoader.getResource("DistanceDistributions.json")!!.readText(Charsets.UTF_8)
        distanceDists = Json.decodeFromString(distrTxt)

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building)
        }

        // Create graphhopper
        hopper = createGraphHopper(
            "C:/Users/strobel/Projekte/esmregio/graphhopperServer/oberbayern-latest.osm.pbf",
            "omod_cache/routing-graph-cache"
        )

        // Create grid (used for speed up)
        grid = makeGrid(gridResolution ?: 500.0)

        // Create routing table
        routingTable = createRoutingTable(grid)

        // Calibration
        calibrationMatrix = mutableMapOf()
        for (activityType in ActivityType.values()) {
            calibrationMatrix[activityType] = null
        }

        if (odFile != null) {
            // Read OD-Matrix that is used for calibration
            val odMatrix = ODMatrix(odFile, geometryFactory)
            // Add TAZ to buildings and cells
            val dummyZones = addTAZInfo(odMatrix)
            zones = grid + dummyZones
            // Get calibration factors based on OD-Matrix
            calibrateWithOD(odMatrix)
        } else {
            zones = grid
        }
    }

    // Factories
    companion object {
        @Suppress("unused")
        fun fromPG(dbUrl: String, dbUser: String, dbPassword: String, areaOsmIds: List<Int>): Omod {
            return fromPG(dbUrl, dbUser, dbPassword, areaOsmIds,
                          cache = true, cachePath = Paths.get("omod_cache/buildings.geojson"),
                          odFile = null, gridResolution = null, seed = null,
                          bufferRadius = 0.0,
                          censusFile = null, regionTypeFile = null
            )
        }
        fun fromPG(
            dbUrl: String, dbUser: String, dbPassword: String, areaOsmIds: List<Int>,
            odFile: File? = null, gridResolution: Double? = null, seed: Long? = null,
            censusFile: File? = null, regionTypeFile: File? = null,
            bufferRadius: Double = 0.0,
            cache: Boolean = true, cachePath: Path = Paths.get("omod_cache/buildings.geojson"),
        ): Omod {
            // Check cache
            val buildingsCollection: GeoJsonFeatureCollection
            if (cache and cachePath.toFile().exists()) {
                buildingsCollection = Json{ ignoreUnknownKeys = true }
                    .decodeFromString(cachePath.toFile().readText(Charsets.UTF_8))
            } else {
                // Load data from geojson files and PostgreSQL database with OSM data
                buildingsCollection = createModelArea(
                    dbUrl = dbUrl,
                    dbUser = dbUser,
                    dbPassword = dbPassword,
                    areaOsmIds = areaOsmIds,
                    bufferRadius = bufferRadius,
                    censusFile = censusFile,
                    regionTypeFile = regionTypeFile
                )
                if (cache) {
                    Files.createDirectories(cachePath.parent)
                    cachePath.toFile().writeText(Json{ encodeDefaults = true }.encodeToString(buildingsCollection))
                }
            }
            val geometryFactory = GeometryFactory()

            return Omod(
                Building.fromGeoJson(buildingsCollection, geometryFactory),
                odFile,
                gridResolution,
                seed,
                geometryFactory
            )
        }
        @Suppress("unused")
        fun fromFile(file: File, odFile: File?= null, gridResolution: Double? = null,
                     seed: Long? = null): Omod {
            val buildingsCollection: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
                .decodeFromString(file.readText(Charsets.UTF_8))

            val geometryFactory = GeometryFactory()
            return Omod(
                Building.fromGeoJson(buildingsCollection, geometryFactory),
                odFile,
                gridResolution,
                seed,
                geometryFactory
            )
        }
        @Suppress("unused")
        fun makeFileFromPG(file: File, dbUrl: String, dbUser: String, dbPassword: String,
                           areaOsmIds: List<Int>,
                           censusFile: File? = null, regionTypeFile: File? = null,
                           bufferRadius: Double = 0.0
        ) {
            // Load data from geojson files and PostgreSQL database with OSM data
            val buildingsCollection = createModelArea(
                dbUrl = dbUrl,
                dbUser = dbUser,
                dbPassword = dbPassword,
                areaOsmIds = areaOsmIds,
                bufferRadius = bufferRadius,
                censusFile = censusFile,
                regionTypeFile = regionTypeFile
            )
            file.writeText(Json{ encodeDefaults = true }.encodeToString(buildingsCollection))
        }
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
        for (x in semiOpenDoubleRange(xMin, xMax, gridResolution)) {
            for (y in semiOpenDoubleRange(yMin, yMax, gridResolution)) {
                val envelope = Envelope(x, x+gridResolution, y, y+gridResolution)
                val cellBuildings = kdTree.query(envelope).map { ((it as KdNode).data as Building) }
                if (cellBuildings.isEmpty()) continue

                // Centroid of all contained buildings
                val featureCentroid = geometryFactory.createMultiPoint(
                    cellBuildings.map { it.point }.toTypedArray()
                ).centroid.coordinate

                grid.add(
                    Cell(
                        coord = featureCentroid,
                        envelope = envelope,
                        buildings = cellBuildings,
                    )
                )
            }
        }
        return grid.toList()
    }

    fun createGraphHopper(osmLoc: String, cacheLoc: String) : GraphHopper {
        val hopper = GraphHopper()
        hopper.osmFile = osmLoc
        hopper.graphHopperLocation = cacheLoc
        hopper.setProfiles(Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false))
        hopper.chPreparationHandler.setCHProfiles(CHProfile("car"))
        hopper.importOrLoad()
        return hopper
    }

    fun createRoutingTable(locations: List<LocationOption>) : Map<LocationOption, Map<LocationOption, Double>> {
        val table = mutableMapOf<LocationOption, Map<LocationOption, Double>>()
        for (origin in locations) {
            table[origin] = locations.associateWith { calcDistance(origin, it, mode) }
        }
        return table
    }

    fun addTAZInfo(odMatrix: ODMatrix) : List<LocationOption> {
        val dummyZones = mutableListOf<LocationOption>()

        // Get buildings in every TAZ and add the TAZ to that building
        for (odRow in odMatrix.rows.values) {
            val tazBuildings = mutableListOf<Building>()

            fastCovers(odRow.geometry, listOf(10000.0, 5000.0, 1000.0), geometryFactory,
                ifNot = { },
                ifDoes = { e ->
                    tazBuildings.addAll(kdTree.query(e).map { ((it as KdNode).data as Building) })
                },
                ifUnsure = { e ->
                    tazBuildings.addAll(kdTree.query(e).map { ((it as KdNode).data as Building) }
                        .filter { odRow.geometry.contains(it.point) })
                }
            )

            val activities = setOf(odRow.originActivity, odRow.destinationActivity)

            if (tazBuildings.isEmpty()) {
                // Create dummy node for TAZ
                val dummyLoc = DummyLocation(
                    coord = odRow.geometry.centroid.coordinate,
                    homeWeight = (ActivityType.HOME in activities).toDouble(),
                    workWeight = (ActivityType.WORK in activities).toDouble(),
                    schoolWeight = (ActivityType.SCHOOL in activities).toDouble(),
                    shoppingWeight = (ActivityType.SHOPPING in activities).toDouble(),
                    otherWeight = (ActivityType.OTHER in activities).toDouble(),
                    taz = odRow.origin
                )
                dummyZones.add(dummyLoc)
            } else {
                for (building in tazBuildings) {
                    // Remember TAZ
                    building.taz = odRow.origin
                }
            }
        }

        for (cell in grid) {
            // Add TAZ to cell
            cell.taz = cell.buildings.groupingBy { it.taz }.eachCount().maxByOrNull { it.value }!!.key
        }

        return dummyZones
    }

    fun calibrateWithOD(odMatrix: ODMatrix, calibrateOrigins: Boolean = true){
        // Check if OD has valid activities. Currently allowed: HOME->WORK, HOME->SCHOOL. Planed: ANY->ANY
        val odActivities = Pair(odMatrix.rows.values.first().originActivity,
                                odMatrix.rows.values.first().destinationActivity)
        require(odActivities in setOf(Pair(ActivityType.HOME, ActivityType.WORK),
                                      Pair(ActivityType.SCHOOL, ActivityType.WORK))) {
            "Only OD-Matrices with Activities HOME->WORK and HOME->SCHOOL are currently supported"
        }
        for (odRow in odMatrix.rows.values) {
            require(odRow.originActivity == odActivities.first) { "OD-Matrix activities not uniform" }
            require(odRow.destinationActivity == odActivities.second) { "OD-Matrix activities not uniform" }
        }

        val tazsInFocusArea = buildings.filter { it.inFocusArea }.mapNotNull { it.taz }.distinct()

        // Calibrate origins
        if (calibrateOrigins) {
            val activityCalibrationMatrix = mutableMapOf<Pair<String?, String>, Double>()
            val omodWeights = mutableMapOf<String, Double>()
            val odWeights = mutableMapOf<String, Double>()

            for (odRow in odMatrix.rows.values) {
                // Calculate omod origin probability. For speed only on zone level.
                val originLocations = zones.filter { it.taz == odRow.origin }

                var omodWeight = 0.0
                for (start in originLocations) {
                    omodWeight += getWeightsNoOrigin(listOf(start), ActivityType.HOME).sum()
                }
                omodWeights[odRow.origin] = omodWeight

                // Calculate OD-Matrix origin probability.
                var odWeight = 0.0
                for (taz in tazsInFocusArea) {
                    odWeight += odRow.destinations[taz]!!
                }
                odWeights[odRow.origin] = odWeight
            }

            // Calculate calibration factors
            val weightSumOD = odWeights.values.sum()
            val weightSumOMOD = omodWeights.values.sum()

            for (odRow in odMatrix.rows.values) {
                // Probability OD:
                val odProb = odWeights[odRow.origin]!! / weightSumOD

                // Probability OMOD:
                val omodProb = omodWeights[odRow.origin]!! / weightSumOMOD

                activityCalibrationMatrix[Pair(null, odRow.origin)] = odProb / omodProb
            }
            calibrationMatrix[odActivities.first] = activityCalibrationMatrix
        }

        // Calibrate transitions. "run" is just a block start in kotlin.
        run {
            val activityCalibrationMatrix = mutableMapOf<Pair<String?, String>, Double>()

            for (odRow in odMatrix.rows.values) {
                val omodWeights = mutableMapOf<String, Double>()
                val odWeights = mutableMapOf<String, Double>()

                val originLocations = zones.filter { it.taz == odRow.origin }

                for (destination in odRow.destinations.keys) {
                    // Calculate omod transition probability. For speed only on zone level.
                    val destinationLocations = zones.filter { it.taz == destination }

                    var omodWeight = 0.0
                    for (start in originLocations) {
                        val homeWeight = getWeightsNoOrigin(listOf(start), ActivityType.HOME).sum()
                        if (homeWeight > 0) {
                            omodWeight += homeWeight * getWeights(start, destinationLocations, ActivityType.WORK).sum()
                        }
                    }
                    omodWeights[destination] = omodWeight

                    // Calculate OD-Matrix origin probability.
                    odWeights[destination] = if ((destination in tazsInFocusArea) || (odRow.origin in tazsInFocusArea) ){
                        odRow.destinations[destination]!!
                    } else {
                        0.0
                    }
                }

                // Calculate calibration factors
                val weightSumOD = odWeights.values.sum()
                val weightSumOMOD = omodWeights.values.sum()

                for (destination in odRow.destinations.keys) {
                    // Probability OD:
                    val odProb = odWeights[destination]!! / weightSumOD

                    // Probability OMOD:
                    val gamgProb = omodWeights[destination]!! / weightSumOMOD

                    activityCalibrationMatrix[Pair(odRow.origin, destination)] = odProb / gamgProb
                }
            }
            calibrationMatrix[odActivities.second] = activityCalibrationMatrix
        }
    }

    /**
     * Initialize population by assigning home and work locations
     * @param n number of agents
     * @param randomFeatures determines whether the population be randomly chosen or as close as possible to the given distributions.
     *                       In the random case, the sampling is still done based on said distribution. Mostly important for small agent numbers.
     * @param inputPopDef sociodemographic distribution of the agents. If null the distributions in Population.json are used.
     */
    fun createAgents(n: Int, randomFeatures: Boolean = false, inputPopDef: Map<String, Map<String,
                     Double>>? = null): List<MobiAgent> {
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
        val agentFeatures: List<Int> // List of indices that map an agent to a features set
        if (randomFeatures) {
            val distr = createCumDist(jointProbability.toDoubleArray())
            agentFeatures = List(n) {sampleCumDist(distr, rng)}
        } else {
            // Assign the features deterministically
            val expectedObservations = jointProbability.map { it * n }.toDoubleArray()
            agentFeatures = List(n) { _ ->
                val j = expectedObservations.withIndex().maxByOrNull { it.value }!!.index // Assign agent to feature with highest E(f)
                expectedObservations[j] -= 1.0 // Reduce E(f) by one
                j
            }
        }

        // Assign home and work
        val homCumDist = getDistrNoOrigin(zones, ActivityType.HOME)

        // Generate population TODO: enable fixed number in focus area, maybe by just stopping after i have enough?
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val schoolDistCache = mutableMapOf<Int, DoubleArray>()
        val agents = List(n) { i ->
            // Sociodemographic features
            val homogenousGroup = features[agentFeatures[i]].first
            val mobilityGroup = features[agentFeatures[i]].second
            val age = features[agentFeatures[i]].third

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
                val workBuildingsCumDist = getDistr(home, workZone.buildings, ActivityType.WORK)
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
                val schoolBuildingsCumDist = getDistr(home, schoolZone.buildings, ActivityType.SCHOOL)
                schoolZone.buildings[sampleCumDist(schoolBuildingsCumDist, rng)]
            } else {
                schoolZone
            }

            // Add the agent to the population
            MobiAgent(i, homogenousGroup, mobilityGroup, age, home, work, school)
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

        // Get cell
        val flexDist = if (type == ActivityType.SHOPPING) {
            getDistr(location, zones, ActivityType.SHOPPING)
        } else {
            getDistr(location, zones, ActivityType.OTHER)
        }
        val flexZone = zones[sampleCumDist(flexDist, rng)]

        // Get location
        return if (flexZone is Cell) {
            val flexBuildingCumDist = if (type == ActivityType.SHOPPING) {
                getDistr(location, flexZone.buildings, ActivityType.SHOPPING)
            } else {
                getDistr(location, flexZone.buildings, ActivityType.OTHER)
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
                     from: ActivityType = ActivityType.HOME) : List<Double?> {
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
                     start: LocationOption) : List<LocationOption> {
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
                           from: ActivityType = ActivityType.HOME): List<Activity> {
        val location = when(from) {
            ActivityType.HOME -> agent.home
            ActivityType.WORK -> agent.work
            ActivityType.SCHOOL -> agent.school
            else -> throw Exception("Start must be either Home, Work, School, or coordinates must be given. Agent: ${agent.id}")
        }
        return getMobilityProfile(agent, weekday, from, location)
    }
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME,
                           start: LocationOption): List<Activity> {
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
            val weekday = weekdays[(i + offset) % weekdays.size]
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

    /**
     * Determine probabilities for the activities
     */
    fun getDistr(origin: LocationOption, destinations: List<LocationOption>,
                 activityType: ActivityType) : DoubleArray {
        val weights = getWeights(origin, destinations, activityType)
        return createCumDist(weights.toDoubleArray())
    }
    fun getDistrNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : DoubleArray {
        val weights = getWeightsNoOrigin(destinations, activityType)
        return createCumDist(weights.toDoubleArray())
    }
    fun getWeights(origin: LocationOption, destinations: List<LocationOption>,
                   activityType: ActivityType): List<Double> {
        return when(activityType) {
            ActivityType.HOME -> getWeightsNoOrigin(destinations, ActivityType.HOME)
            ActivityType.WORK -> {
                val priors = getWeightsNoOrigin(destinations, ActivityType.WORK)
                val distances = calcDistances(origin, destinations)
                val distObj = distanceDists.home_work[origin.regionType]!!
                val distr = LogNorm(distObj.shape, distObj.scale)
                destinations.mapIndexed { i, destination ->
                    val kFactor = getKFactor(activityType, origin, destination)
                    kFactor * priors[i] * distr.density(distances[i])
                }
            }
            ActivityType.SCHOOL -> {
                val priors = getWeightsNoOrigin(destinations, ActivityType.SCHOOL)
                val distances = calcDistances(origin, destinations)
                val distObj = distanceDists.home_school[origin.regionType]!!
                val distr = LogNorm(distObj.shape, distObj.scale)
                List (destinations.size){ i ->
                    priors[i] * distr.density(distances[i])
                }
            }
            ActivityType.SHOPPING -> {
                // Flexible activities don't leave dummy location except OD-Matrix defines it
                if ((origin is DummyLocation) && (origin.shoppingWeight == 0.0) ) {
                    destinations.map { if (origin == it)  1.0 else 0.0 }
                    // Normal case
                } else {
                    val priors = getWeightsNoOrigin(destinations, ActivityType.SHOPPING)
                    val distances = calcDistances(origin, destinations)
                    val distObj = distanceDists.any_shopping[origin.regionType]!!
                    val distr = LogNorm(distObj.shape, distObj.scale)
                    List(destinations.size) { i ->
                        priors[i] * distr.density(distances[i])
                    }
                }
            }
            else -> {
                // Flexible activities don't leave dummy location except OD-Matrix defines it
                if ((origin is DummyLocation) && (origin.otherWeight == 0.0) ) {
                    destinations.map { if (origin == it)  1.0 else 0.0 }
                    // Normal case
                } else {
                    val priors = getWeightsNoOrigin(destinations, ActivityType.OTHER)
                    val distances = calcDistances(origin, destinations)
                    val distObj = distanceDists.any_other[origin.regionType]!!
                    val distr = LogNorm(distObj.shape, distObj.scale)
                    List(destinations.size) { i ->
                        priors[i] * distr.density(distances[i])
                    }
                }
            }
        }
    }
    fun getWeightsNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : List<Double> {
        return when(activityType) {
            ActivityType.HOME -> {
                destinations.map { destination ->
                    val kFactor = getKFactor(activityType, null, destination)
                    kFactor * destination.homeWeight
                }
            }
            ActivityType.WORK -> destinations.map { it.workWeight }
            ActivityType.SCHOOL -> destinations.map { it.schoolWeight }
            ActivityType.SHOPPING -> destinations.map { it.shoppingWeight }
            else -> destinations.map { it.otherWeight }
        }
    }

    fun calcDistances(origin: LocationOption, destinations: List<LocationOption>) : List<Double> {
        return if (routingTable.containsKey(origin)) {
            val table = routingTable[origin]!!
            destinations.map { table[it] ?: calcDistance(origin, it, RoutingMode.BEELINE) }
        } else {
            destinations.map { calcDistance(origin, it, RoutingMode.BEELINE) } // TODO fix these beelines and decide on routing issue
        }
    }
    fun calcDistance(origin: LocationOption, destination: LocationOption, mode: RoutingMode) : Double {
        return when (mode) {
            RoutingMode.BEELINE -> calcDistanceBeeline(origin, destination)
            RoutingMode.GRAPHHOPPER -> calcDistanceGH(origin, destination)
        }
    }
    fun calcDistanceBeeline(origin: LocationOption, destination: LocationOption) : Double {
        return origin.coord.distance(destination.coord)
    }
    fun calcDistanceGH (origin: LocationOption, destination: LocationOption) : Double {
        // Dummy locations are not inside the GraphHopper graph -> fall back to beeline
        return if ((origin is DummyLocation) || (destination is DummyLocation)) {
            calcDistanceBeeline(origin, destination)
        } else {
            val req = GHRequest(
                origin.latlonCoord.x,
                origin.latlonCoord.y,
                destination.latlonCoord.x,
                destination.latlonCoord.y
            )
            val rsp = hopper.route(req)

            // If routing didn't work fall back to beeline
            if (rsp.hasErrors()) {
                origin.coord.distance(destination.coord)
            } else {
                rsp.best.distance
            }
        }
    }

    fun getKFactor(activityType: ActivityType, origin: LocationOption?, destination: LocationOption) : Double {
        val originTAZ = if (origin == null) {
            null
        } else {
            origin.taz!!
        }
        // K-factor from OD-Matrix
        val kFactor = if (calibrationMatrix[activityType] != null) {
            calibrationMatrix[activityType]!![Pair(originTAZ, destination.taz!!)]!!
        } else {
            1.0
        }
        return kFactor
    }
}
