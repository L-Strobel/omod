package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.FileReader
import org.locationtech.jts.index.kdtree.KdTree
import org.locationtech.jts.geom.Coordinate
import kotlinx.serialization.json.*
import java.io.File
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import de.uniwuerzburg.StochasticBundle as SB

/**
 * General purpose mobility demand generator (gamg)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Gamg(buildingsPath: String, odPath: String?, gridResolution: Double) {
    val buildings: List<Building>
    val kdTree: KdTree
    private val grid: List<Cell>
    private val zones: List<LocationOption> // Grid + DummyLocations for commuting locations
    private val calibrationMatrix: Map<Pair<String, String>, Double>?
    private val populationDef: PopulationDef
    private val activityDataMap: ActivityDataMap
    private val distanceDists: DistanceDistributions
    private val geometryFactory = GeometryFactory()

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

        // Read buildings data
        val reader = BufferedReader(FileReader(buildingsPath))

        // Skip header
        reader.readLine()
        // Read body
        var id = 0
        buildings = mutableListOf()
        reader.forEachLine {
            val line = it.split(",")

            val lat = line[2].toDouble()
            val lon = line[3].toDouble()
            val latlonCoord = Coordinate(lat, lon)
            // Transform the lat lons to cartesian coordinates
            val coord = latlonToMercator(lat, lon)

            buildings.add(
                Building(
                    id = id,
                    osmID = line[0].toInt(),
                    coord = coord,
                    latlonCoord = latlonCoord,
                    area = line[1].toDouble(),
                    population = line[4].toDouble(),
                    landuse = Landuse.valueOf(line[5]),
                    regionType = line[6].toInt(),
                    nShops = line[7].toDouble(),
                    nOffices = line[8].toDouble(),
                    nSchools = line[9].toDouble(),
                    nUnis = line[10].toDouble(),
                    inFocusArea = line[11].toBoolean(),
                    taz = null,
                    point = geometryFactory.createPoint(coord)
                )
            )
            id += 1
        }

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building)
        }

        // Create grid (used for speed up)
        grid = makeGrid(gridResolution)

        // Calibration
        if (odPath != null) {
            // Read OD-Matrix that is used for calibration
            val odMatrix = ODMatrix(odPath, geometryFactory)
            // Add TAZ to buildings and cells
            val dummyZones = addTAZInfo(odMatrix)
            zones = grid + dummyZones
            // Get calibration factors based on OD-Matrix
            calibrationMatrix = calibrateWithOD(odMatrix)
        } else {
            calibrationMatrix = null
            zones = grid
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

            if (tazBuildings.isEmpty()) {
                // Create dummy node for TAZ
                // TODO: Weight should only be one for the activity the OD is from, currently work.
                // TODO: Also for other and shopping (reason is flexible loc should also be possible at a dummy node, i.e. commuting location)
                val dummyLoc = DummyLocation(
                    coord = odRow.geometry.centroid.coordinate,
                    homeWeight = 0.0,
                    workWeight = 1.0,
                    schoolWeight = 0.0,
                    shoppingWeight = 1.0,
                    otherWeight = 1.0,
                    regionType = 0,
                    taz = odRow.origin,
                    avgDistanceToSelf = 1.0
                )
                dummyZones.add(dummyLoc)
            } else {
                // Remember TAZ
                for (building in tazBuildings) {
                    building.taz = odRow.origin
                }
            }
        }

        // Add TAZ to cell
        for (cell in grid) {
            cell.taz = cell.buildings.groupingBy { it.taz }.eachCount().maxByOrNull { it.value }!!.key
        }

        return dummyZones
    }

    // TODO this process will work for commuting, if i want all activities ... rethink
    fun calibrateWithOD(odMatrix: ODMatrix) : Map<Pair<String, String>, Double> {
        val calibrationMatrix = mutableMapOf<Pair<String, String>, Double>()

        for (odRow in odMatrix.rows.values) {
            // Calculate gamg transition probability. For speed only on zone level.
            val gamgWeights = mutableMapOf<String, Double>()
            val originLocations = zones.filter { it.taz == odRow.origin}

            for (destination in odRow.destinations.keys) {
                val destinationLocations = zones.filter { it.taz == destination}

                var gamgWeight = 0.0
                for (start in originLocations) {
                    if (start.homeWeight > 0) {
                        for (stop in destinationLocations) {
                            gamgWeight += start.homeWeight * getWorkWeightPosterior(start, stop)
                        }
                    }
                }
                gamgWeights[destination] = gamgWeight
            }

            // Calculate calibration factors
            val weightSumOD = odRow.destinations.values.sum()
            val weightSumGAMG = gamgWeights.values.sum()

            for (destination in odRow.destinations.keys) {
                // Probability OD:
                val odProb = odRow.destinations[destination]!! / weightSumOD

                // Probability GAMG:
                val gamgProb = gamgWeights[destination]!! / weightSumGAMG

                calibrationMatrix[Pair(odRow.origin, destination)] = odProb / gamgProb
            }
        }
        return calibrationMatrix
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
            val distr = SB.createCumDist(jointProbability.toDoubleArray())
            agentFeatures = List(n) {SB.sampleCumDist(distr)}
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
        val homCumDist = SB.createCumDist(zones.map { it.homeWeight }.toDoubleArray())

        // Generate population
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val schoolDistCache = mutableMapOf<Int, DoubleArray>()
        val agents = List(n) { i ->
            // Sociodemographic features
            val homogenousGroup = features[agentFeatures[i]].first
            val mobilityGroup = features[agentFeatures[i]].second
            val age = features[agentFeatures[i]].third

            // Get home zone (might be cell or dummy is node)
            val homeZoneID = SB.sampleCumDist(homCumDist)
            val homeZone = zones[homeZoneID]

            // Get home location
            val home = if (homeZone is Cell) {
                // IS building
                val buildingsHomeProb = homeZone.buildings.map { it.homeWeight }.toDoubleArray()
                homeZone.buildings[SB.createAndSampleCumDist(buildingsHomeProb)]
            } else {
                // IS dummy location
                homeZone
            }

            // Get work zone (might be cell or dummy is node)
            val workZoneCumDist = workDistCache.getOrPut(homeZoneID) {
                getWorkDistr(homeZone, zones)
            }
            val workZone = zones[SB.sampleCumDist(workZoneCumDist)]

            // Get work location
            val work = if (workZone is Cell) {
                val workBuildingsCumDist = getWorkDistr(home, workZone.buildings)
                workZone.buildings[SB.sampleCumDist(workBuildingsCumDist)]
            } else {
                workZone
            }

            // Get school cell
            val schoolZoneCumDist = schoolDistCache.getOrPut(homeZoneID) {
                getSchoolDistr(homeZone, zones)
            }
            val schoolZone = zones[SB.sampleCumDist(schoolZoneCumDist)]

            // Get school location
            val school = if (schoolZone is Cell) {
                val schoolBuildingsCumDist = getSchoolDistr(home, schoolZone.buildings)
                schoolZone.buildings[SB.sampleCumDist(schoolBuildingsCumDist)]
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
        require(type != ActivityType.HOME) // Home not flexible
        require(type != ActivityType.WORK) // Work not flexible

        // Get cell
        val flexDist = if (type == ActivityType.SHOPPING) {
            getShoppingDistr(location, zones)
        } else {
            getOtherDistr(location, zones)
        }
        val flexZone = zones[SB.sampleCumDist(flexDist)]

        // Get location
        return if (flexZone is Cell) {
            val flexBuildingCumDist = if (type == ActivityType.SHOPPING) {
                getShoppingDistr(location, flexZone.buildings)
            } else {
                getOtherDistr(location, flexZone.buildings)
            }
            flexZone.buildings[SB.sampleCumDist(flexBuildingCumDist)]
        } else {
            flexZone
        }
    }

    /**
     * Get the activity chain
     */
    fun getActivityChain(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME) : List<ActivityType> {
        val data = activityDataMap.get(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from)
        val i = SB.sampleCumDist(data.distr)
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
            val i = SB.sampleCumDist(mixture.distr)
            val stayTimes = SB.sampleNDGaussian(mixture.means[i], mixture.covariances[i]).toList()
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
    private fun getWeightPosterior(origin: LocationOption, destination: LocationOption, distr: SB.LogNorm,
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

    fun getWorkWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        // Calibration factor from OD-Matrix
        val calibrationFactor = calibrationMatrix?.let { it[Pair(origin.taz!!, destination.taz!!)] } ?: run { 1.0 }

        val distObj = distanceDists.home_work[origin.regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        return  calibrationFactor * getWeightPosterior(origin, destination, distr, destination.workWeight)
    }

    fun getWorkDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return SB.createCumDist(destinations.map { getWorkWeightPosterior(origin, it) }.toDoubleArray())
    }

    fun getSchoolWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        val distObj = distanceDists.home_school[origin.regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        return getWeightPosterior(origin, destination, distr, destination.schoolWeight)
    }

    fun getSchoolDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return SB.createCumDist(destinations.map { getSchoolWeightPosterior(origin, it) }.toDoubleArray())
    }

    fun getShoppingWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        val distObj = distanceDists.any_shopping[origin.regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        return getWeightPosterior(origin, destination, distr, destination.shoppingWeight)
    }

    fun getShoppingDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return SB.createCumDist(destinations.map { getShoppingWeightPosterior(origin, it) }.toDoubleArray())
    }

    fun getOtherWeightPosterior(origin: LocationOption, destination: LocationOption) : Double {
        val distObj = distanceDists.any_other[origin.regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        return getWeightPosterior(origin, destination, distr, destination.otherWeight)
    }

    fun getOtherDistr(origin: LocationOption, destinations: List<LocationOption>) : DoubleArray {
        return SB.createCumDist(destinations.map { getOtherWeightPosterior(origin, it) }.toDoubleArray())
    }

}
