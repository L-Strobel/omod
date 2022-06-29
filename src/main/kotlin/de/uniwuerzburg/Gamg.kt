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
    private val zones: List<LocationOption>
    private val calibrationMatrix: Map<Pair<String, String>, Double>
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
                )
            )
            id += 1
        }

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building.id)
        }

        // Get calibration factors based on OD-Matrix
        val (resultCalMat, dummyZones) = calibrateWithOD(odPath!!)
        calibrationMatrix = resultCalMat

        // Create grid (used for speed up)
        val grid = makeGrid(gridResolution)

        // All the options a trip can go to. This with dummy entries for far away locations.
        zones = grid + dummyZones
    }

    // TODO this process will work for commuting, if i want all activities i have to do IPA probably
    fun calibrateWithOD(odPath: String) : Pair<Map<Pair<String, String>, Double>, List<LocationOption>> {
        val calibrationMatrix = mutableMapOf<Pair<String, String>, Double>()

        val dummyZones = mutableListOf<LocationOption>()

        // Read OD-Matrix that is used for calibration
        val odMatrix = ODMatrix(odPath, geometryFactory)

        // Get buildings in every taz
        val locationsInTaz = mutableMapOf<String, List<LocationOption>>()
        for (odRow in odMatrix.rows.values) {
            val envelope = odRow.geometry.envelopeInternal!!
            val buildingIds = kdTree.query(envelope).map { ((it as KdNode).data as Int) }

            val tazBuildings = buildings.slice(buildingIds).filter { odRow.geometry.contains(geometryFactory.createPoint(it.coord)) }

            if (tazBuildings.isEmpty()) {
                // Create Dummy node for TAZ
                val taz = TAZ(
                    coord = odRow.geometry.centroid.coordinate,
                    population = 1.0,
                    workWeight = 1.0,
                    nShops = 1.0,
                    nSchools = 1.0,
                    nUnis = 1.0,
                    regionType = 0,
                    inFocusArea = false,
                    taz = odRow.origin
                )
                locationsInTaz[odRow.origin] = listOf(taz)
                dummyZones.add(taz)
            } else {
                // Remember TAZ
                for (building in tazBuildings) {
                    building.taz = odRow.origin
                }
                locationsInTaz[odRow.origin] = tazBuildings
            }
        }

        //
        val tazs = odMatrix.rows.values.map { it.origin }
        for (origin in tazs) {
            val gamgWeights = mutableMapOf<String, Double>()
            for (destination in tazs) {
                val originLocations = locationsInTaz[origin]!!
                val destinationLocations = locationsInTaz[destination]!!

                var gamgWeight = 0.0
                for (start in originLocations) { // .sortedBy { it.population * it.inFocusArea.toInt()}.takeLast(1000) {
                    if (start.population * start.inFocusArea.toInt() > 0) {
                        val distObj = distanceDists.home_work[start.regionType]!!
                        val distr = SB.LogNorm(distObj.shape, distObj.scale)
                        for (stop in destinationLocations) { //s.sortedBy { it.workWeight }.takeLast(1000)) {
                            val distance =
                                start.coord.distance(stop.coord) // TODO handle infinities that arise than taz node to taz node
                            gamgWeight += start.population * start.inFocusArea.toInt() * stop.workWeight * distr.density(
                                distance
                            )
                        }
                    }
                }
                gamgWeights[destination] = gamgWeight
            }

            val tripSumOD = odMatrix.rows[origin]!!.destinations.values.sum()
            val weightSumGAMG = gamgWeights.values.sum()

            for (destination in tazs) {
                // Probability OD:
                val odProb = odMatrix.rows[origin]!!.destinations[destination]!! / tripSumOD

                // Probability GAMG:
                val gamgProb = gamgWeights[destination]!! / weightSumGAMG

                calibrationMatrix[Pair(origin, destination)] = odProb / gamgProb
            }
        }
        return Pair(calibrationMatrix, dummyZones)
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
        for (x in xMin..xMax step gridResolution) {
            for (y in yMin..yMax step gridResolution) {
                val envelope = Envelope(x, x+gridResolution, y, y+gridResolution)
                val buildingIds = kdTree.query(envelope).map { ((it as KdNode).data as Int) }
                if (buildingIds.isEmpty()) continue

                val cellBuildings = buildings.slice(buildingIds)

                // Centroid of all contained buildings
                val featureCentroid = geometryFactory.createMultiPointFromCoords(
                    cellBuildings.map { it.coord }.toTypedArray()
                ).centroid.coordinate

                // Transform the lat lons to cartesian coordinates
                val latlonCentroid = mercatorToLatLon(featureCentroid.x, featureCentroid.y)

                // Most common region type
                val regionType = cellBuildings.groupingBy { it.regionType }.eachCount().maxByOrNull { it.value }!!.key

                // Most common taz
                val taz = cellBuildings.groupingBy { it.taz }.eachCount().maxByOrNull { it.value }!!.key

                // Calculate aggregate features of cell
                val population = cellBuildings.sumOf { it.population * it.inFocusArea.toInt()}
                val priorWorkWeight = cellBuildings.sumOf { it.workWeight }
                val nShops = cellBuildings.sumOf { it.nShops }
                val nOffices = cellBuildings.sumOf { it.nOffices }
                val nSchools = cellBuildings.sumOf { it.nSchools }
                val nUnis = cellBuildings.sumOf { it.nUnis }
                val inFocusArea = cellBuildings.map { it.inFocusArea }.any()
                grid.add(Cell(population, priorWorkWeight, envelope, cellBuildings, featureCentroid,
                              latlonCentroid, regionType, nShops, nOffices, nSchools, nUnis, inFocusArea, taz))
            }
        }
        return grid.toList()
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
        val homCumDist = SB.createCumDist(zones.map { it.population * it.inFocusArea.toInt() }.toDoubleArray())

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
                val buildingsHomeProb = homeZone.buildings.map { it.population * it.inFocusArea.toInt() }.toDoubleArray()
                homeZone.buildings[SB.createAndSampleCumDist(buildingsHomeProb)]
            } else {
                // IS dummy location
                homeZone
            }

            // Get work zone (might be cell or dummy is node)
            val workZoneCumDist = workDistCache.getOrPut(homeZoneID) {
                getWorkDistr(homeZone, zones, homeZone.regionType)
            }
            val workZone = zones[SB.sampleCumDist(workZoneCumDist)]

            // Get work location
            val work = if (workZone is Cell) {
                val workBuildingsCumDist = getWorkDistr(home, workZone.buildings, home.regionType)
                workZone.buildings[SB.sampleCumDist(workBuildingsCumDist)]
            } else {
                workZone
            }

            // Get school cell
            val schoolZoneCumDist = schoolDistCache.getOrPut(homeZoneID) {
                getSchoolDistr(homeZone.coord, zones,  homeZone.regionType)
            }
            val schoolZone = zones[SB.sampleCumDist(schoolZoneCumDist)]

            // Get school location
            val school = if (schoolZone is Cell) {
                val schoolBuildingsCumDist = getSchoolDistr(home.coord, schoolZone.buildings, home.regionType)
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
     * @param regionType region type of current location according to RegioStar7. 0 indicates an undefined region type.
     */
    fun findFlexibleLoc(location: Coordinate, type: ActivityType, regionType: Int = 0): LocationOption {
        require(type != ActivityType.HOME) // Home not flexible
        require(type != ActivityType.WORK) // Work not flexible

        // Get cell
        val flexDist = if (type == ActivityType.SHOPPING) {
            getShoppingDistr(location, zones, regionType)
        } else {
            getOtherDistr(location, zones, regionType)
        }
        val flexZone = zones[SB.sampleCumDist(flexDist)]

        // Get location
        return if (flexZone is Cell) {
            val flexBuildingCumDist = if (type == ActivityType.SHOPPING) {
                getShoppingDistr(location, flexZone.buildings, regionType)
            } else {
                getOtherDistr(location, flexZone.buildings, regionType)
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
                        else -> findFlexibleLoc(locations[i-1].coord, activity, locations[i-1].regionType)
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
     * Get that a discrete location is chosen based on the distance to an origin and pdf(distance)
     * used for both cells and buildings
     */
    private fun getProbsByDistance(origin: Coordinate, targets: List<Coordinate>, baseDist: SB.LogNorm) : DoubleArray {
        return DoubleArray (targets.size) { i ->
            // Probability due to distance to home
            val distance = origin.distance(targets[i])
            baseDist.density(distance)
        }
    }

    /**
     * Determine coordinates of individual activities
     */
    fun getWorkDistr(home: LocationOption, options: List<LocationOption>, regionType: Int) : DoubleArray {
        // Prior probabilities
        val priorWeights = options.map { it.workWeight * calibrationMatrix[Pair(home.taz!!, it.taz!!)]!! }
        // Probability because of distance
        val distObj = distanceDists.home_work[regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(home.coord, targets, distr)
        // Total
        val probabilities = DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
        return SB.createCumDist(probabilities)
    }

    fun getSchoolDistr(homeCoords: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        // Prior probabilities
        val priorWeights = options.map { it.nSchools }
        // Probability because of distance
        val distObj = distanceDists.home_school[regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(homeCoords, targets, distr)
        // Total
        val probabilities = DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
        return SB.createCumDist(probabilities)
    }

    fun getShoppingDistr(location: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        // Prior probabilities
        val priorWeights = options.map { it.nShops }
        // Probability because of distance
        val distObj = distanceDists.any_shopping[regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(location, targets, distr)
        // Total
        val probabilities = DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
        return SB.createCumDist(probabilities)
    }

    fun getOtherDistr(location: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        val distObj = distanceDists.any_other[regionType]!!
        val distr = SB.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(location, targets, distr)
        return SB.createCumDist(distanceWeights)
    }
}
