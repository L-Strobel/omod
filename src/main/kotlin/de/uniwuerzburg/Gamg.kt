package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.locationtech.jts.index.kdtree.KdTree
import kotlinx.serialization.json.*
import java.io.File
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/**
 * General purpose mobility demand generator (gamg)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Gamg(val buildings: List<Building>, odFile: File?, gridResolution: Double?, seed: Long?,
           private val geometryFactory: GeometryFactory = GeometryFactory()) {
    val kdTree: KdTree
    private val grid: List<Cell>
    private val zones: List<LocationOption> // Grid + DummyLocations for commuting locations
    private val calibrationMatrix: MutableMap<ActivityType, Map<Pair<String?, String>, Double>?>
    private val populationDef: PopulationDef
    private val activityDataMap: ActivityDataMap
    private val distanceDists: DistanceDistributions
    private val rng: Random = if (seed != null) Random(seed) else Random()

    init {
        // Get population distribution
        val popTxt = Gamg::class.java.classLoader.getResource("Population.json")!!.readText(Charsets.UTF_8)
        populationDef = Json.decodeFromString(popTxt)

        // Get activity chain data
        val actTxt = Gamg::class.java.classLoader.getResource("ActivityGroups.json")!!.readText(Charsets.UTF_8)
        val activityGroups: List<ActivityGroup> = Json.decodeFromString(actTxt)
        activityDataMap = ActivityDataMap(activityGroups)

        // Get distance distributions
        val distrTxt = Gamg::class.java.classLoader.getResource("DistanceDistributions.json")!!.readText(Charsets.UTF_8)
        distanceDists = Json.decodeFromString(distrTxt)

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building)
        }

        // Create grid (used for speed up)
        grid = makeGrid(gridResolution ?: 500.0)

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
        fun fromPG(dbUrl: String, dbUser: String, dbPassword: String, areaOsmIds: List<Int>): Gamg {
            return fromPG(dbUrl, dbUser, dbPassword, areaOsmIds,
                          cache = true, cachePath = Paths.get("omod_cache/buildings.geojson"),
                          odFile = null, gridResolution = null, seed = null,
                          bufferRadius = 0.0,
                          censusFile = null, regionTypeFile = null
            )
        }
        fun fromPG(dbUrl: String, dbUser: String, dbPassword: String, areaOsmIds: List<Int>,
                   odFile: File? = null, gridResolution: Double? = null, seed: Long? = null,
                   censusFile: File? = null, regionTypeFile: File? = null,
                   bufferRadius: Double = 0.0,
                   cache: Boolean = true, cachePath: Path = Paths.get("omod_cache/buildings.geojson"),
                   ): Gamg {
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
                    cachePath.toFile().writeText(Json.encodeToString(buildingsCollection))
                }
            }
            val geometryFactory = GeometryFactory()
            return Gamg(
                Building.fromGeoJson(buildingsCollection, geometryFactory),
                odFile,
                gridResolution,
                seed,
                geometryFactory
            )
        }
        @Suppress("unused")
        fun fromFile(file: File, odFile: File?= null, gridResolution: Double? = null,
                     seed: Long? = null): Gamg {
            val buildingsCollection: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
                .decodeFromString(file.readText(Charsets.UTF_8))

            val geometryFactory = GeometryFactory()
            return Gamg(
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
            file.writeText(Json.encodeToString(buildingsCollection))
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
            val gamgWeights = mutableMapOf<String, Double>()
            val odWeights = mutableMapOf<String, Double>()

            for (odRow in odMatrix.rows.values) {
                // Calculate gamg origin probability. For speed only on zone level.
                val originLocations = zones.filter { it.taz == odRow.origin }

                var gamgWeight = 0.0
                for (start in originLocations) {
                    gamgWeight += getHomeWeightPosterior(start)
                }
                gamgWeights[odRow.origin] = gamgWeight

                // Calculate OD-Matrix origin probability.
                var odWeight = 0.0
                for (taz in tazsInFocusArea) {
                    odWeight += odRow.destinations[taz]!!
                }
                odWeights[odRow.origin] = odWeight
            }

            // Calculate calibration factors
            val weightSumOD = odWeights.values.sum()
            val weightSumGAMG = gamgWeights.values.sum()

            for (odRow in odMatrix.rows.values) {
                // Probability OD:
                val odProb = odWeights[odRow.origin]!! / weightSumOD

                // Probability GAMG:
                val gamgProb = gamgWeights[odRow.origin]!! / weightSumGAMG

                activityCalibrationMatrix[Pair(null, odRow.origin)] = odProb / gamgProb
            }
            calibrationMatrix[odActivities.first] = activityCalibrationMatrix
        }

        // Calibrate transitions. "run" is just a block start in kotlin.
        run {
            val activityCalibrationMatrix = mutableMapOf<Pair<String?, String>, Double>()

            for (odRow in odMatrix.rows.values) {
                val gamgWeights = mutableMapOf<String, Double>()
                val odWeights = mutableMapOf<String, Double>()

                val originLocations = zones.filter { it.taz == odRow.origin }

                for (destination in odRow.destinations.keys) {
                    // Calculate gamg transition probability. For speed only on zone level.
                    val destinationLocations = zones.filter { it.taz == destination }

                    var gamgWeight = 0.0
                    for (start in originLocations) {
                        val homeProb = getHomeWeightPosterior(start)
                        if (homeProb > 0) {
                            for (stop in destinationLocations) {
                                gamgWeight += homeProb * getWorkWeightPosterior(start, stop)
                            }
                        }
                    }
                    gamgWeights[destination] = gamgWeight

                    // Calculate OD-Matrix origin probability.
                    odWeights[destination] = if ((destination in tazsInFocusArea) || (odRow.origin in tazsInFocusArea) ){
                        odRow.destinations[destination]!!
                    } else {
                        0.0
                    }
                }

                // Calculate calibration factors
                val weightSumOD = odWeights.values.sum()
                val weightSumGAMG = gamgWeights.values.sum()

                for (destination in odRow.destinations.keys) {
                    // Probability OD:
                    val odProb = odWeights[destination]!! / weightSumOD

                    // Probability GAMG:
                    val gamgProb = gamgWeights[destination]!! / weightSumGAMG

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
        val homCumDist = getHomeDistr(zones)

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
                val buildingsHomeDist = getHomeDistr(homeZone.buildings)
                homeZone.buildings[sampleCumDist(buildingsHomeDist, rng)]
            } else {
                // IS dummy location
                homeZone
            }

            // Get work zone (might be cell or dummy is node)
            val workZoneCumDist = workDistCache.getOrPut(homeZoneID) {
                getWorkDistr(homeZone, zones)
            }
            val workZone = zones[sampleCumDist(workZoneCumDist, rng)]

            // Get work location
            val work = if (workZone is Cell) {
                val workBuildingsCumDist = getWorkDistr(home, workZone.buildings)
                workZone.buildings[sampleCumDist(workBuildingsCumDist, rng)]
            } else {
                workZone
            }

            // Get school cell
            val schoolZoneCumDist = schoolDistCache.getOrPut(homeZoneID) {
                getSchoolDistr(homeZone, zones)
            }
            val schoolZone = zones[sampleCumDist(schoolZoneCumDist, rng)]

            // Get school location
            val school = if (schoolZone is Cell) {
                val schoolBuildingsCumDist = getSchoolDistr(home, schoolZone.buildings)
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
            getShoppingDistr(location, zones)
        } else {
            getOtherDistr(location, zones)
        }
        val flexZone = zones[sampleCumDist(flexDist, rng)]

        // Get location
        return if (flexZone is Cell) {
            val flexBuildingCumDist = if (type == ActivityType.SHOPPING) {
                getShoppingDistr(location, flexZone.buildings)
            } else {
                getOtherDistr(location, flexZone.buildings)
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
    fun run(n: Int, weekday: String = "undefined", safeToJson: Boolean = false) : List<MobiAgent> {
        val agents = createAgents(n)
        for (agent in agents) {
            agent.profile = getMobilityProfile(agent, weekday)
        }
        if (safeToJson) {
            val output = agents.map { formatOutput(it) }
            File("out.json").writeText(Json.encodeToString(output))
        }
        return agents
    }

    /**
     * Determine probabilities for the activities
     */
    private fun getWeightPosterior(origin: LocationOption, destination: LocationOption, distr: LogNorm,
                                   priorWeight: Double) : Double {
        // Probability because of distance
        val distance = if (origin == destination) {
            origin.avgDistanceToSelf // 0.0 for Buildings
        } else {
            origin.coord.distance(destination.coord)
        }
        val distanceWeight = distr.density(distance)

        return priorWeight * distanceWeight
    }

    fun getHomeWeightPosterior(destination: LocationOption) : Double {
        // Calibration factor from OD-Matrix
        val calibrationFactor = if (calibrationMatrix[ActivityType.HOME] != null) {
            calibrationMatrix[ActivityType.HOME]!![Pair(null, destination.taz!!)]!!
        } else {
            1.0
        }
        return  calibrationFactor * destination.homeWeight
    }

    fun getHomeDistr(destinations: List<LocationOption>) : DoubleArray {
        return createCumDist(destinations.map { getHomeWeightPosterior(it) }.toDoubleArray())
    }

    fun getWorkWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        // Calibration factor from OD-Matrix
        val calibrationFactor = if (calibrationMatrix[ActivityType.WORK] != null) {
            calibrationMatrix[ActivityType.WORK]!![Pair(origin.taz!!, destination.taz!!)]!!
        } else {
            1.0
        }

        val distObj = distanceDists.home_work[origin.regionType]!!
        val distr = LogNorm(distObj.shape, distObj.scale)
        return  calibrationFactor * getWeightPosterior(origin, destination, distr, destination.workWeight)
    }

    fun getWorkDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return createCumDist(destinations.map { getWorkWeightPosterior(origin, it) }.toDoubleArray())
    }

    fun getSchoolWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        val distObj = distanceDists.home_school[origin.regionType]!!
        val distr = LogNorm(distObj.shape, distObj.scale)
        return getWeightPosterior(origin, destination, distr, destination.schoolWeight)
    }

    fun getSchoolDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return createCumDist(destinations.map { getSchoolWeightPosterior(origin, it) }.toDoubleArray())
    }

    fun getShoppingWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        // Flexible activities don't leave dummy location except OD-Matrix defines it
        if ((origin is DummyLocation) && (origin.shoppingWeight == 0.0) ) {
            return if (origin == destination) {
                1.0
            } else {
                0.0
            }
        }
        // Normal case
        val distObj = distanceDists.any_shopping[origin.regionType]!!
        val distr = LogNorm(distObj.shape, distObj.scale)
        return getWeightPosterior(origin, destination, distr, destination.shoppingWeight)
    }

    fun getShoppingDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return createCumDist(destinations.map { getShoppingWeightPosterior(origin, it) }.toDoubleArray())
    }

    fun getOtherWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        // Flexible activities don't leave dummy location except OD-Matrix defines it
        if ((origin is DummyLocation) && (origin.otherWeight == 0.0) ) {
            return if (origin == destination) {
                1.0
            } else {
                0.0
            }
        }
        // Normal case
        val distObj = distanceDists.any_other[origin.regionType]!!
        val distr = LogNorm(distObj.shape, distObj.scale)
        return getWeightPosterior(origin, destination, distr, destination.otherWeight)
    }

    fun getOtherDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return createCumDist(destinations.map { getOtherWeightPosterior(origin, it) }.toDoubleArray())
    }

}
