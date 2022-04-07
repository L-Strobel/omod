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
    private val buildings: MutableList<Building>
    private val kdTree: KdTree
    private val grid: List<Cell>
    private val activityDataMap: ActivityDataMap
    private val populationDef: PopulationDef
    private val distanceDists: DistanceDistributions

    init {
        val reader = BufferedReader(FileReader(buildingsPath))

        // Skip header
        reader.readLine()
        // Read body
        buildings = mutableListOf()
        reader.forEachLine {
            val line = it.split(",")
            buildings.add(
                Building(
                    coord = Coordinate(line[1].toDouble(), line[2].toDouble()),
                    area = line[0].toDouble(),
                    population = line[3].toDouble(),
                    landuse = Landuse.valueOf(line[4]),
                    regionType = line[5].toInt(),
                    nShops = line[6].toDouble(),
                    nOffices = line[7].toDouble()
                )
            )
        }

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEachIndexed { i, building ->
            kdTree.insert(building.coord, i)
        }

        // Create grid
        grid = mutableListOf()
        val xMin = buildings.minOfOrNull { it.coord.x } ?:0.0
        val yMin = buildings.minOfOrNull { it.coord.y } ?:0.0
        val xMax = buildings.maxOfOrNull { it.coord.x } ?:0.0
        val yMax = buildings.maxOfOrNull { it.coord.y } ?:0.0
        val geometryFactory = GeometryFactory() // For centroid calculation
        for (x in xMin..xMax step gridResolution) {
            for (y in yMin..yMax step gridResolution) {
                val envelope = Envelope(x, x+gridResolution, y, y+gridResolution)
                val buildingIds = kdTree.query(envelope).map { ((it as KdNode).data as Int) }

                if (buildingIds.isEmpty()) continue

                // Centroid of all contained buildings
                val featureCentroid = geometryFactory.createMultiPointFromCoords(
                    buildings.slice(buildingIds).map { it.coord }.toTypedArray()
                ).centroid.coordinate

                // Most common region type
                val regionType = buildings.slice(buildingIds).groupingBy { it.regionType }.eachCount().maxByOrNull { it.value }!!.key

                // Calculate aggregate features of cell
                var population = 0.0
                var priorWorkWeight = 0.0
                var nShops = 0.0
                var nOffices = 0.0
                for ( i in buildingIds) {
                    population += buildings[i].population
                    nShops += buildings[i].nShops
                    nOffices += buildings[i].nOffices
                    priorWorkWeight += buildings[i].priorWorkWeight
                }
                grid.add(Cell(population, priorWorkWeight, envelope, buildingIds, featureCentroid, regionType, nShops, nOffices))
            }
        }
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

        // Assign home and work // TODO do schools
        val homCumDist = StochasticBundle.createCumDist(grid.map { it.population }.toDoubleArray())

        // Calculate work probability of cell or building
        fun getWorkDist(home: Coordinate, targets: List<Coordinate>, priorWeights: List<Double>,
                        regionType: Int = 0) : DoubleArray {
            // Get distance distribution, Currently always lognormal
            val distObj = distanceDists.home_work[regionType]!!
            val homeWorkDist = StochasticBundle.LogNorm(distObj.shape, distObj.scale)
            // Get the resulting total distribution by factoring in priors
            // val probabilities = getProbsByDistance(home, targets, priorWeights, homeWorkDist)
            return StochasticBundle.createCumDist(priorWeights.toDoubleArray())
        }

        // Generate population
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val agents = List(n) { i ->
            // Sociodemographic features
            val homogenousGroup = features[agentFeatures[i]].first
            val mobilityGroup = features[agentFeatures[i]].second
            val age = features[agentFeatures[i]].third

            // Get home cell
            val homeCell = StochasticBundle.sampleCumDist(homCumDist)

            // Get home building
            val homeCellBuildingIDs = grid[homeCell].buildingIds
            val homeCellBuildings = buildings.slice(homeCellBuildingIDs)
            val inHomeCellID = StochasticBundle.createAndSampleCumDist(homeCellBuildings.map { it.population }.toDoubleArray())
            val home = homeCellBuildingIDs[inHomeCellID]
            val homeCoords = buildings[home].coord
            val homeRegion = buildings[home].regionType

            // Get work cell
            val workCumDist = workDistCache.getOrPut(homeCell) {
                val targets = grid.map { it.featureCentroid }
                val weights = grid.map { it.priorWorkWeight }
                getWorkDist(homeCoords, targets, weights, homeRegion)
            }
            val workCell = StochasticBundle.sampleCumDist(workCumDist)

            // Get work building
            val workCellBuildingIDs = grid[workCell].buildingIds
            val workCellBuildings = buildings.slice(workCellBuildingIDs)
            val targets = workCellBuildings.map { it.coord }
            val weights = workCellBuildings.map { it.priorWorkWeight }
            val inWorkCellID = StochasticBundle.sampleCumDist(getWorkDist(homeCoords, targets, weights, homeRegion))
            val work = workCellBuildingIDs[inWorkCellID]

            // Add the agent to the population
            MobiAgent(i, homogenousGroup, mobilityGroup, age, home, work)
        }
        return agents
    }

    /**
     * Get the location of an activity with flexible location with a given current location
     * @param location Coordinates of current location
     * @param type Activity type
     * @param regionType region type of current location according to RegioStar7. 0 indicates an undefined region type.
     * TODO shopping locations not the best right now
     */
    fun findFlexibleLoc(location: Coordinate, type: ActivityType, regionType: Int = 0): Int {
        require(type != ActivityType.HOME) // Home not flexible
        require(type != ActivityType.WORK) // Work not flexible
        // Get distance distribution
        val distObj = when (type){
            ActivityType.SHOPPING -> distanceDists.any_shopping[regionType]!!
            else -> distanceDists.any_other[regionType]!!
        }
        val distr = StochasticBundle.LogNorm(distObj.shape, distObj.scale)

        // Get cell
        val secDist = if (type == ActivityType.SHOPPING) {
            // val priorWeights = grid.map { it.nShops }
            // getProbsByDistance(location, grid.map { it.featureCentroid }, priorWeights, distr)
            grid.map { it.nShops }.toDoubleArray()
        } else {
            getProbsByDistance(location, grid.map { it.featureCentroid }, distr)
        }
        val secCell = StochasticBundle.createAndSampleCumDist(secDist)

        // Get building
        val secCellBuildingsID = grid[secCell].buildingIds
        val secCellBuildings = buildings.slice(secCellBuildingsID)
        val secDistBuilding = if (type == ActivityType.SHOPPING) {
            // val priorWeights = secCellBuildings.map { it.nShops }
            // getProbsByDistance(location, secCellBuildings.map { it.coord }, priorWeights, distr)
            secCellBuildings.map { it.nShops }.toDoubleArray()
        } else {
            getProbsByDistance(location, secCellBuildings.map { it.coord }, distr)
        }
        return secCellBuildingsID[StochasticBundle.createAndSampleCumDist(secDistBuilding)]
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
                     fromBuildingID: Int) : List<Coordinate> {
        val locations = mutableListOf<Building>()
        activityChain.forEachIndexed { i, activity ->
            if (i == 0){
                locations.add(buildings[fromBuildingID])
            } else {
                locations.add(
                    when (activity) {
                        ActivityType.HOME -> buildings[agent.home]
                        ActivityType.WORK -> buildings[agent.work]
                        else ->  buildings[findFlexibleLoc(locations[i-1].coord, activity, locations[i-1].regionType)]
                    }
                )
            }
        }
        return locations.map { it.coord }
    }

    /**
     * Get the mobility profile for the given agent.
     */
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined",
                           from: ActivityType = ActivityType.HOME): List<Activity> {
        val buildingID = when(from) {
            ActivityType.HOME -> agent.home
            ActivityType.WORK -> agent.work
            else -> throw Exception("Start must be either Home, Work, or coordinates must be given. Agent: ${agent.id}")
        }
        return getMobilityProfile(agent, weekday, from, buildingID)
    }
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined",
                           from: ActivityType = ActivityType.HOME, fromCoords: Coordinate): List<Activity> {
        val fromBuildingID = kdTree.query(fromCoords).data as Int
        return getMobilityProfile(agent, weekday, from, fromBuildingID)
    }
    fun getMobilityProfile(agent: MobiAgent, weekday: String = "undefined", from: ActivityType = ActivityType.HOME,
                           fromBuildingID: Int): List<Activity> {
        val activityChain = getActivityChain(agent, weekday, from)
        val stayTimes = getStayTimes(activityChain, agent, weekday, from)
        val locations = getLocations(agent, activityChain, fromBuildingID)
        return List(activityChain.size) { i ->
            Activity(activityChain[i], stayTimes[i], locations[i].x, locations[i].y)
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
    private fun getProbsByDistance(origin: Coordinate, targets: List<Coordinate>,
                                   priorWeights: List<Double>, baseDist: StochasticBundle.LogNorm) : DoubleArray {
        require(targets.size == priorWeights.size) { "Targets and prior weights are not the same shape!" }
        val distanceWeights = getProbsByDistance(origin, targets, baseDist)
        return DoubleArray(targets.size) { i ->
            distanceWeights[i] * priorWeights[i]
        }
    }
    private fun getProbsByDistance(origin: Coordinate, targets: List<Coordinate>, baseDist: StochasticBundle.LogNorm) : DoubleArray {
        return DoubleArray (targets.size) { i ->
            // Probability due to distance to home
            val distance = origin.distance(targets[i])
            baseDist.density(distance)
        }
    }
}
