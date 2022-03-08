package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
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

            val probabilities = DoubleArray (targets.size) { i ->
                // Probability due to distance to home
                var distance = home.distance(targets[i])
                if (distance == 0.0) distance = 1E-3
                var prob = homeWorkDist.density(distance)
                // Prior probability due to landuse
                prob *= priorWeights[i]
                prob
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

            MobiAgent(i, home, work)
        }
        return agents
    }
}
