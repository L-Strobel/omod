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

/**
 * General purpose mobility demand generator (gamg)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Gamg(buildingsPath: String, gridResolution: Double) {
    val buildings: List<Building>
    val kdTree: KdTree
    private val grid: List<Cell>
    private val activityDataMap: ActivityDataMap
    private val populationDef: PopulationDef
    private val distanceDists: DistanceDistributions
    private val geometryFactory = GeometryFactory()

    init {
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

        // Create grid
        grid = makeGrid(gridResolution)

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

        // Calibrate
        val calibrationMatrix = calibrateWithOD("C:/Users/strobel/Projekte/esmregio/gamg/OD_matrix.csv")
        println("Test")
    }

    // TODO rename grid to tazs and change type to locationOption than add commute nodes

    // Calibrate priors:
    // Read OD-Prob _/
    // Get prob gamg
    // HOME -> Trivial
    // Others -> Get prob for all homes -> Get prob for all homes -> Calc total prob
    // Scale to OD-Prob

    // TODO this process will work for commuting, if i want all activities i have to do IPA probably
    fun calibrateWithOD(odPath: String) : Map<Pair<String, String>, Double> {
        val calibrationMatrix = mutableMapOf<Pair<String, String>, Double>()

        // Read OD-Matrix that is used for calibration
        val odMatrix = ODMatrix(odPath, geometryFactory)

        // Get buildings in every taz
        val locationsInTaz = mutableMapOf<String, List<LocationOption>>()
        for (odRow in odMatrix.rows.values) {
            val envelope = odRow.geometry.envelopeInternal!! // TODO get buildings that are really in Geometry
            val buildingIds = kdTree.query(envelope).map { ((it as KdNode).data as Int) }

            if (buildingIds.isEmpty()) {
                // Create Dummy node for TAZ
                val taz = TAZ(
                    coord = odRow.geometry.centroid.coordinate,
                    population = 1.0,
                    workWeight = 1.0,
                    nShops = 1.0,
                    nSchools = 1.0,
                    nUnis = 1.0,
                    inFocusArea = false
                )
                locationsInTaz[odRow.origin] = listOf(taz)
                // TODO add to grid?

            } else {
                // Remember TAZ
                val tazBuildings = buildings.slice(buildingIds)
                for (building in tazBuildings) {
                    building.taz = odRow.origin
                }
                locationsInTaz[odRow.origin] = buildings.slice(buildingIds)
            }
        }

        //
        val tazs = odMatrix.rows.values.map { it.origin }
        for (origin in tazs) {
            val gamgWeights = mutableMapOf<String, Double>()
            for (destination in tazs) {
                val originLocations = locationsInTaz[origin]!!
                val destinationLocations = locationsInTaz[destination]!!

                // Probability because of distance
                val distObj = distanceDists.home_work[0]!!
                val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)

                var gamgWeight = 0.0
                for (start in originLocations.sortedBy { it.population }.takeLast(100)) {
                    for (stop in destinationLocations.sortedBy { it.workWeight }.takeLast(100)) {
                        val distance = start.coord.distance(stop.coord) // TODO handle infinities that arise than taz node to taz node
                        gamgWeight += start.population * stop.workWeight * distr.density(distance)
                    }
                }

                /*
                val originCentroid = geometryFactory.createMultiPointFromCoords(
                    originLocations.map { it.coord }.toTypedArray()
                ).centroid.coordinate

                val destinationCentroid = geometryFactory.createMultiPointFromCoords(
                    destinationLocations.map { it.coord }.toTypedArray()
                ).centroid.coordinate

                // TODO how to handle self distance?
                val distance = originCentroid.distance(destinationCentroid)

                // Prior probabilities
                val priorWeight = destinationLocations.sumOf { it.workWeight }

                // Probability because of distance
                val distObj = distanceDists.home_work[0]!!
                val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)
                val distanceProb = distr.density(distance)

                gamgWeights[destination] = priorWeight * distanceProb
                */
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
        return calibrationMatrix
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

                // Calculate aggregate features of cell
                val population = cellBuildings.sumOf { it.population * it.inFocusArea.toInt()}
                val priorWorkWeight = cellBuildings.sumOf { it.workWeight }
                val nShops = cellBuildings.sumOf { it.nShops }
                val nOffices = cellBuildings.sumOf { it.nOffices }
                val nSchools = cellBuildings.sumOf { it.nSchools }
                val nUnis = cellBuildings.sumOf { it.nUnis }
                val inFocusArea = cellBuildings.map { it.inFocusArea }.any()
                grid.add(Cell(population, priorWorkWeight, envelope, buildingIds, featureCentroid,
                              latlonCentroid, regionType, nShops, nOffices, nSchools, nUnis, inFocusArea))
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
    fun createAgents(n: Int, randomFeatures: Boolean = false, inputPopDef: Map<String, Map<String, Double>>? = null): List<MobiAgent> {
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
            val distr = StochasticBundle.createCumDist(jointProbability.toDoubleArray())
            agentFeatures = List(n) {StochasticBundle.sampleCumDist(distr)}
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
        val homCumDist = StochasticBundle.createCumDist(grid.map { it.population * it.inFocusArea.toInt() }.toDoubleArray())

        // Generate population
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val schoolDistCache = mutableMapOf<Int, DoubleArray>()
        val agents = List(n) { i ->
            // Sociodemographic features
            val homogenousGroup = features[agentFeatures[i]].first
            val mobilityGroup = features[agentFeatures[i]].second
            val age = features[agentFeatures[i]].third

            // Get home cell
            val homeCellID = StochasticBundle.sampleCumDist(homCumDist)
            val homeCell = grid[homeCellID]

            // Get home building
            val homeCellBuildings = buildings.slice(homeCell.buildingIds)
            val inHomeCellID = StochasticBundle.createAndSampleCumDist(homeCellBuildings.map { it.population * it.inFocusArea.toInt() }.toDoubleArray())
            val home = homeCellBuildings[inHomeCellID]

            // Get work cell
            val workCellCumDist = workDistCache.getOrPut(homeCellID) {
                getWorkDistr(homeCell.featureCentroid, grid, home.regionType)
            }
            val workCell = grid[StochasticBundle.sampleCumDist(workCellCumDist)]

            // Get work building
            val workCellBuildings = buildings.slice(workCell.buildingIds)
            val workBuildingsCumDist = getWorkDistr(home.coord, workCellBuildings, home.regionType)
            val inWorkCellID = StochasticBundle.sampleCumDist(workBuildingsCumDist)
            val work = workCellBuildings[inWorkCellID]

            // Get school cell
            val schoolCellCumDist = schoolDistCache.getOrPut(homeCellID) {
                getSchoolDistr(homeCell.featureCentroid, grid,  home.regionType)
            }
            val schoolCell = grid[StochasticBundle.sampleCumDist(schoolCellCumDist)]

            // Get school building
            val schoolCellBuildings = buildings.slice(schoolCell.buildingIds)
            val schoolBuildingsCumDist = getSchoolDistr(home.coord, schoolCellBuildings, home.regionType)
            val inSchoolCellID = StochasticBundle.sampleCumDist(schoolBuildingsCumDist)
            val school = schoolCellBuildings[inSchoolCellID]

            // Add the agent to the population
            MobiAgent(i, homogenousGroup, mobilityGroup, age, home.id, work.id, school.id)
        }
        return agents
    }

    /**
     * Get the location of an activity with flexible location with a given current location
     * @param location Coordinates of current location
     * @param type Activity type
     * @param regionType region type of current location according to RegioStar7. 0 indicates an undefined region type.
     */
    fun findFlexibleLoc(location: Coordinate, type: ActivityType, regionType: Int = 0): Int {
        require(type != ActivityType.HOME) // Home not flexible
        require(type != ActivityType.WORK) // Work not flexible

        // Get cell
        val flexDist = if (type == ActivityType.SHOPPING) {
            getShoppingDistr(location, grid, regionType)
        } else {
            getOtherDistr(location, grid, regionType)
        }
        val flexCell = grid[StochasticBundle.sampleCumDist(flexDist)]

        // Get building
        val flexCellBuildings = buildings.slice(flexCell.buildingIds)
        val flexBuildingCumDist = if (type == ActivityType.SHOPPING) {
            getShoppingDistr(location, flexCellBuildings, regionType)
        } else {
            getOtherDistr(location, flexCellBuildings, regionType)
        }
        val inFlexCellID = StochasticBundle.sampleCumDist(flexBuildingCumDist)
        return flexCellBuildings[inFlexCellID].id
    }

    /**
     * Get the activity chain
     */
    fun getActivityChain(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME) : List<ActivityType> {
        val data = activityDataMap.get(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from)
        val i = StochasticBundle.sampleCumDist(data.distr)
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
            val i = StochasticBundle.sampleCumDist(mixture.distr)
            val stayTimes = StochasticBundle.sampleNDGaussian(mixture.means[i], mixture.covariances[i]).toList()
            // Handle negative values. Last stay is always until the end of the day, marked by null
            stayTimes.map { if (it < 0 ) 0.0 else it } + null
        }
    }

    /**
     * Get the activity locations for the given agent.
     * @param agent The agent
     * @param activityChain The activities the agent will undertake that day
     * @param fromBuildingID The building the day starts at
     */
    fun getLocations(agent: MobiAgent, activityChain: List<ActivityType>,
                     fromBuildingID: Int) : List<Building> {
        val locations = mutableListOf<Building>()
        activityChain.forEachIndexed { i, activity ->
            if (i == 0){
                locations.add(buildings[fromBuildingID])
            } else {
                locations.add(
                    when (activity) {
                        ActivityType.HOME -> buildings[agent.homeID]
                        ActivityType.WORK -> buildings[agent.workID]
                        ActivityType.SCHOOL -> buildings[agent.schoolID]
                        else ->  buildings[findFlexibleLoc(locations[i-1].coord, activity, locations[i-1].regionType)]
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
        val buildingID = when(from) {
            ActivityType.HOME -> agent.homeID
            ActivityType.WORK -> agent.workID
            ActivityType.SCHOOL -> agent.schoolID
            else -> throw Exception("Start must be either Home, Work, School, or coordinates must be given. Agent: ${agent.id}")
        }
        return getMobilityProfile(agent, weekday, from, buildingID)
    }
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME,
                           fromBuildingID: Int): List<Activity> {
        val activityChain = getActivityChain(agent, weekday, from)
        val stayTimes = getStayTimes(activityChain, agent, weekday, from)
        val locations = getLocations(agent, activityChain, fromBuildingID)
        return List(activityChain.size) { i ->
            Activity(activityChain[i], stayTimes[i], locations[i].id, locations[i].latlonCoord.x, locations[i].latlonCoord.y)
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
            File("out.json").writeText(Json.encodeToString(agents))
        }
        return agents
    }

    /**
     * Get that a discrete location is chosen based on the distance to an origin and pdf(distance)
     * used for both cells and buildings
     */
    private fun getProbsByDistance(origin: Coordinate, targets: List<Coordinate>, baseDist: StochasticBundle.LogNorm) : DoubleArray {
        return DoubleArray (targets.size) { i ->
            // Probability due to distance to home
            val distance = origin.distance(targets[i])
            baseDist.density(distance)
        }
    }

    /**
     * Determine coordinates of individual activities
     */
    fun getWorkDistr(homeCoords: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        // Prior probabilities
        val priorWeights = options.map { it.workWeight }
        // Probability because of distance
        val distObj = distanceDists.home_work[regionType]!!
        val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(homeCoords, targets, distr)
        // Total
        val probabilities = DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
        return StochasticBundle.createCumDist(probabilities)
    }

    fun getSchoolDistr(homeCoords: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        // Prior probabilities
        val priorWeights = options.map { it.nSchools }
        // Probability because of distance
        val distObj = distanceDists.home_school[regionType]!!
        val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(homeCoords, targets, distr)
        // Total
        val probabilities = DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
        return StochasticBundle.createCumDist(probabilities)
    }

    fun getShoppingDistr(location: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        // Prior probabilities
        val priorWeights = options.map { it.nShops }
        // Probability because of distance
        val distObj = distanceDists.any_shopping[regionType]!!
        val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(location, targets, distr)
        // Total
        val probabilities = DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
        return StochasticBundle.createCumDist(probabilities)
    }

    fun getOtherDistr(location: Coordinate, options: List<LocationOption>, regionType: Int) : DoubleArray {
        val distObj = distanceDists.any_other[regionType]!!
        val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)
        val targets = options.map { it.coord }
        val distanceWeights = getProbsByDistance(location, targets, distr)
        return StochasticBundle.createCumDist(distanceWeights)
    }
}
