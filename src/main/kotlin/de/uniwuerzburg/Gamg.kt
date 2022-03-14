package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.FileReader
import org.locationtech.jts.index.kdtree.KdTree
import org.locationtech.jts.geom.Coordinate
import kotlinx.serialization.json.*
import java.io.File
import org.apache.commons.math3.distribution.WeibullDistribution
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode

/**
 * General purpose mobility demand generator (gamg)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */
class Gamg(buildingsPath: String, gridResolution: Double) {
    private val buildings: MutableList<Building>
    private val kdTree: KdTree
    private val grid: List<Cell>
    private val activityData: ActivityData

    init {
        val reader = BufferedReader(FileReader(buildingsPath))

        // Skip header
        reader.readLine()
        // Read body
        buildings = mutableListOf()
        reader.forEachLine {
            val line = it.split(",")
            buildings.add(Building(
                coord = Coordinate(line[1].toDouble(), line[2].toDouble()),
                area = line[0].toDouble(),
                population = line[3].toDouble(),
                landuse = Landuse.getFromStr(line[4])))
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

                // Calculate aggregate features of cell
                var population = 0.0
                var priorWorkWeight = 0.0
                for ( i in buildingIds) {
                    population += buildings[i].population
                    priorWorkWeight += buildings[i].landuse.getWorkWeight()
                }
                grid.add(Cell(population, priorWorkWeight, envelope, buildingIds, featureCentroid))
            }
        }

        // Get activity chain data, TODO change to gaussian
        val path = "C:/Users/strobel/Projekte/PythonPkgs/valactimod/valactimod/ActivityChainData.json"
        activityData = Json.decodeFromString(File(path).readText(Charsets.UTF_8))
    }

    /**
     * Initialize population by assigning home and work locations
     */
    fun createAgents(n: Int): List<MobiAgent> {
        // Init distributions
        val homCumDist = StochasticBundle.createCumDist(grid.map { it.population }.toDoubleArray())
        val homeWorkDist = WeibullDistribution(0.71, 10.5)

        // Calculate work probability of cell or building
        fun getWorkDist(home: Coordinate, targets: List<Coordinate>, priorWeights: List<Double>) : DoubleArray {
            require(targets.size == priorWeights.size)

            val distanceWeights = getProbsByDistance(home, targets, homeWorkDist)
            val probabilities = DoubleArray (targets.size) { i ->
                priorWeights[i] * distanceWeights[i]
            }
            return StochasticBundle.createCumDist(probabilities)
        }

        // Generate population
        val workDistCache = mutableMapOf<Int, DoubleArray>() // Cache for speed up
        val agents = List(n) { i ->
            // Get home cell
            val homeCell = StochasticBundle.sampleCumDist(homCumDist)

            // Get home building
            val homeCellBuildings = buildings.slice(grid[homeCell].buildingIds)
            val home = StochasticBundle.createAndSampleCumDist(homeCellBuildings.map { it.population }.toDoubleArray())
            val homeCoords = buildings[home].coord

            // Get work cell
            val workCumDist = workDistCache.getOrPut(homeCell) {
                val targets = grid.map { it.featureCentroid }
                val weights = grid.map { it.priorWorkWeight }
                getWorkDist(homeCoords, targets, weights)
            }
            val workCell = StochasticBundle.sampleCumDist(workCumDist)

            // Get work building
            val workCellBuildings = buildings.slice(grid[workCell].buildingIds)
            val targets = workCellBuildings.map { it.coord }
            val weights = workCellBuildings.map { it.landuse.getWorkWeight() }
            val work = StochasticBundle.sampleCumDist(getWorkDist(homeCoords, targets, weights))

            // TODO add sociodemographic features
            MobiAgent(i, home, work)
        }
        return agents
    }
    /**
     * Get the location of a secondary location with a given current location
     */
    fun findSecondary(location: Coordinate): Int {
        // TODO differentiate between activity types
        val currSecDist = WeibullDistribution(0.71, 10.5)

        // Get cell
        val secDist = getProbsByDistance(location, grid.map { it.featureCentroid }, currSecDist)
        val secCell = StochasticBundle.createAndSampleCumDist(secDist)

        // Get building
        val secCellBuildings = buildings.slice(grid[secCell].buildingIds)
        val secDistBuilding = getProbsByDistance(location, secCellBuildings.map { it.coord }, currSecDist)
        return StochasticBundle.createAndSampleCumDist(secDistBuilding)
    }

    /**
     * Get the activity chain
     * TODO: Give agent and day
     */
    fun getActivityChain() : List<ActivityType> {
        val data = activityData.nodes[1]!!.leafs[1]!!
        val activityChainID = StochasticBundle.createAndSampleCumDist(data.activityChainWeights.toDoubleArray())
        return ActivityType.getListFromStr(data.activityChains[activityChainID])
    }

    /**
     * Get the stay times given an activity chain
     * TODO: Change to gaussian
     */
    fun getStayTimes(activityChain: List<ActivityType>) : List<Double> {
        if (activityChain.size == 1) {
            return listOf(1440.0)
        } else {
            val activityChainID = ActivityType.getStrFromList(activityChain)
            val data = activityData.nodes[1]!!.leafs[1]!!
            return data.stayTimeData[activityChainID]!![0]
        }
    }

    /**
     * Get the activity locations for the given agent.
     */
    fun getLocations(agent: MobiAgent, activityChain: List<ActivityType>) : List<Coordinate> {
        require(activityChain[0] == ActivityType.HOME) // TODO add other start options
        val locations = mutableListOf<Coordinate>()
        activityChain.forEachIndexed { i, activity ->
            locations.add(
                when (activity) {
                    ActivityType.HOME -> buildings[agent.home].coord
                    ActivityType.WORK -> buildings[agent.work].coord
                    ActivityType.SECONDARY ->  buildings[findSecondary(locations[i-1])].coord
                }
            )
        }
        return locations
    }

    /**
     * Get the mobility profile for the given agent.
     * TODO and given day
     */
    fun getMobilityProfile(agent: MobiAgent): List<Activity> {
        val activityChain = getActivityChain()
        val stayTimes = getStayTimes(activityChain)
        val locations = getLocations(agent, activityChain)
        return List(activityChain.size) { i ->
            Activity(activityChain[i], stayTimes[i], locations[i].x, locations[i].y)
        }
    }

    /**
     * Determine the demand of the area for n agents at one day.
     * Optionally safe to json.
     */
    fun run(n: Int, safeToJson: Boolean = false) : List<MobiAgent> {
        val agents = createAgents(n)
        for (agent in agents) {
            agent.profile = getMobilityProfile(agent)
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
    private fun getProbsByDistance(origin: Coordinate, targets: List<Coordinate>, baseDist: WeibullDistribution) : DoubleArray {
        return DoubleArray (targets.size) { i ->
            // Probability due to distance to home
            var distance = origin.distance(targets[i])
            if (distance == 0.0) distance = 1E-3
            baseDist.density(distance)
        }
    }
}
