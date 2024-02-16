package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.io.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.apache.commons.math3.ml.clustering.CentroidCluster
import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer
import org.apache.commons.math3.ml.distance.DistanceMeasure
import org.apache.commons.math3.ml.distance.EuclideanDistance
import org.apache.commons.math3.random.JDKRandomGenerator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.pow

/**
 * Centroid container for bisecting KMeans.
 * Stores the centroid as well as the associated sum of squared errors (sse) and distance sum.
 *
 * @param centroid the centroid that will be stored in the container
 * @param distanceMeasure the distance metric to use for sse and distance sum calculation
 */
private class CentroidContainer<T: Clusterable>(
    val centroid: CentroidCluster<T>,
    distanceMeasure: DistanceMeasure
) {
    val sse = calcSSE()
    val sumDist = calcDistanceSum(distanceMeasure)

    private fun calcSSE(): Double {
        var rslt = 0.0
        for (point in centroid.points) {
            for (i in 0 until point.point.size) {
                rslt += (point.point[i] - centroid.center.point[i]).pow(2)
            }
        }
        return rslt
    }

    private fun calcDistanceSum(distanceMeasure: DistanceMeasure): Double {
        var rslt = 0.0
        for (point in centroid.points) {
            rslt += distanceMeasure.compute(centroid.center.point, point.point)
        }
        return rslt
    }
}

/**
 * Runs the bisecting k-means algorithm
 *
 * @param precision determines at what average distance between point and associated centroid the algorithm stops
 * @param points the points to cluster
 * @param minCluster minimum number of clusters (will be ignored if minCluster < number points)
 *
 * @return centroids of the clusters
 */
fun <T: Clusterable> bisectingKMeans(precision: Double, points: Collection<T>, minCluster: Int = 4)
    : List<CentroidCluster<T>> {
    val distanceMeasure = EuclideanDistance()
    // Fixed seed ensures the cells in a run are the same as in the cached routing data.
    val rng = JDKRandomGenerator(1)

    // Minimum number of clusters
    val minClusterAdj = min(points.size, minCluster)

    // Initial cluster
    val centroids = KMeansPlusPlusClusterer<T>(1, Int.MAX_VALUE, distanceMeasure, rng)
        .cluster(points).map { CentroidContainer(it, distanceMeasure) }.toMutableList()

    // Split clusters until precision is reached
    val clusterer = KMeansPlusPlusClusterer<T>(2, Int.MAX_VALUE, distanceMeasure, rng)
    while ((centroids.size < minClusterAdj) || (centroids.sumOf { it.sumDist } / points.size > precision)) {
        // Find cluster with max sse
        val nextSplit = centroids.maxByOrNull { it.sse } ?: break

        // Split it
        val newCentroids = clusterer.cluster(nextSplit.centroid.points)
            .map { CentroidContainer(it, distanceMeasure) }
        centroids.remove( nextSplit )
        centroids.addAll( newCentroids )
    }
    return centroids.map{ it.centroid }
}

/**
 * Assign each building a routing cell so that the average distance between a building and its cell is less
 * than the desired precision.
 * Assignment is done with the bisecting k-means algorithm.
 *
 * @param precision determines at what average distance between point and associated centroid the algorithm stops
 * @param buildings the buildings for which routing cells are determined
 * @param geometryFactory the geometry factory  used to create new geometries
 * @param transformer the CRS transformation method
 * @param startID The ids/hash-keys of the routing cells are [startID, startID+1, ..., startID+buildings.size]
 *
 * @return routing cells
 */
fun cluster(precision: Double, buildings: List<Building>, geometryFactory: GeometryFactory,
            transformer: CRSTransformer, startID: Int = 0
) : List<Cell> {
    val centroids = bisectingKMeans(precision, buildings)

    var id = startID
    val grid = mutableListOf<Cell>()
    for (centroid in centroids) {
        val cellBuildings = centroid.points
        val featureCentroid = Coordinate(centroid.center.point[0], centroid.center.point[1])

        val latlonCoord = transformer.toLatLon( geometryFactory.createPoint(featureCentroid) ).coordinate

        val cell = Cell(
            id = id,
            coord = featureCentroid,
            latlonCoord = latlonCoord,
            buildings = cellBuildings,
        )

        grid.add(cell)
        id += 1
    }
    return grid.toList()
}

/**
 * Group buildings into routing cells with bisecting-k-Means clustering for faster sampling.
 * The resolution of the routing grid is cut in half with every 10km distance from the focus area.
 *
 * @param focusAreaPrecision determines at what average distance between point and associated centroid the algorithm
 * stops. This value will only count for buildings in the focus area. Buildings in the buffer area are clustered to
 * cells with less precision.
 * @param buildings the buildings for which routing cells are determined
 * @param geometryFactory geometry factory to use to create new geometries
 * @param transformer CRS transformer to use
 *
 * @return routing cells
 */
@Suppress("unused")
fun makeClusterGrid(focusAreaPrecision: Double, buildings: List<Building>,
                    geometryFactory: GeometryFactory, transformer: CRSTransformer
) : List<Cell> {
    // Get cluster in focus area
    val focusAreaBuildings = buildings.filter { it.inFocusArea }
    val cells = cluster(focusAreaPrecision, buildings.filter { it.inFocusArea }, geometryFactory, transformer)
        .toMutableList()

    // Get cluster in buffer area with gradually declining resolution
    val bufferBuildings = buildings.filter { !it.inFocusArea }

    // Boundary of focus area
    val faBoundary = geometryFactory.createMultiPoint(
        focusAreaBuildings.map { it.point }.toTypedArray()
    ).convexHull()

    // Determine distance to focus area and level of detail
    val buildingGroups = mutableMapOf<Int, MutableList<Building>>()
    for (building in bufferBuildings) {
        val distance = faBoundary.distance(building.point).toInt()

        // Quadratic precision decrease by distance of number of clusters in 10km chunks
        val nClusterDivisor = 2 + (distance / 10_000).toDouble().pow(2).toInt()

        if(buildingGroups.containsKey(nClusterDivisor)) {
            buildingGroups[nClusterDivisor]!!.add(building)
        } else {
            buildingGroups[nClusterDivisor] = mutableListOf(building)
        }
    }

    for ((nClusterDivisor, buildingsAtDistance) in buildingGroups) {
        val startID = cells.maxOf { it.id } + 1
        cells.addAll(
            cluster(
                focusAreaPrecision*nClusterDivisor, buildingsAtDistance, geometryFactory, transformer, startID)
        )
    }

    return cells.toList()
}

/**
 *     // Get cluster in buffer area with gradually declining resolution
 */
fun getBufferBuildingClusters (focusAreaPrecision: Double, focusAreaBoundary: Geometry, bufferBuildings: List<Building>,
                               geometryFactory: GeometryFactory, transformer: CRSTransformer, startID: Int) : MutableList<Cell>  {

    var cells = mutableListOf<Cell>()

    // Determine distance to focus area and level of detail
    val buildingGroups = mutableMapOf<Int, MutableList<Building>>()
    for (building in bufferBuildings) {
        val distance = focusAreaBoundary.distance(building.point).toInt()

        // Quadratic precision decrease by distance of number of clusters in 10km chunks
        val nClusterDivisor = 2 + (distance / 10_000).toDouble().pow(2).toInt()

        if(buildingGroups.containsKey(nClusterDivisor)) {
            buildingGroups[nClusterDivisor]!!.add(building)
        } else {
            buildingGroups[nClusterDivisor] = mutableListOf(building)
        }
    }

    for ((nClusterDivisor, buildingsAtDistance) in buildingGroups) {
        cells.addAll(
            cluster(
                focusAreaPrecision*nClusterDivisor, buildingsAtDistance, geometryFactory, transformer, startID)
        )
    }

    return cells
}

/**
 * Group buildings into traffic assignment zones (cells) with predefined geometry.
 * Calculates cell centroids using building centroids
 * Cells outside Focus area are not considered yet...
 *
 * @param zoneGeometriFile geojson file with geometries of traffic assignment zones (cells)
 * @param buildings the buildings which are assigned to cells
 * @param transformer CRS transformer to use
 *
 * @return routing cells
 */
@Suppress("unused")
fun makeClusterGridFromFile(tazFile: File, buildings: List<Building>, transformer: CRSTransformer ) : List<Cell> {

    // Get TAZs from file
    val taz : GeoJsonNoProperties = de.uniwuerzburg.omod.io.json.decodeFromString(tazFile.readText(Charsets.UTF_8))
    var zones = arrayOf<Geometry>()
    if (taz is GeoJsonFeatureCollectionNoProperties) {
        zones = taz.features.map { it.geometry.toJTS(geometryFactory) }.toTypedArray()
    }

    // Get Buildings in FocusArea
    val focusAreaBuildings = buildings.filter { it.inFocusArea }

    // Init empty Cells
    val grid = mutableListOf<Cell>()

    // create cell buildings arrays
    val cellBuildingLists = MutableList(zones.size) { mutableListOf<Building>() }

    // Structure for aggregated cell information
    val simpleTAZs = mutableListOf<OutputSimpleTAZ>()

    // Fill Cells with buildings
    println("Fill Focus Area Cells")
    for ((n, b) in focusAreaBuildings.withIndex()) {
        for((i, z) in zones.withIndex()) {
            if(z.contains(transformer.toLatLon( b.point ))) {
                cellBuildingLists[i].add(b)
                break
            }
        }
    }
    println("Aggregate Cell Information")

    var id = 0
    for ((i,z) in zones.withIndex()) {

        // Calc Cell Centroid from Building Areas
        // if cell contains buildings
        var centroid = arrayOf(0.0,0.0)
        if(cellBuildingLists[i].isNotEmpty()) {
            for (b in cellBuildingLists[i]) {
                val point: DoubleArray = b.getPoint()
                for (j in centroid.indices) {
                    centroid[j] += point[j]
                }
            }
            for (j in centroid.indices) {
                centroid[j] /= cellBuildingLists[i].size.toDouble()
            }
        // if cell is empty
        } else {
            centroid = arrayOf(z.centroid.x, z.centroid.y)
        }

        val featureCentroid = Coordinate(centroid[0], centroid[1])
        val latlonCoord = transformer.toLatLon( geometryFactory.createPoint(featureCentroid) ).coordinate

        // Create Cells and TAZs
        val cell = Cell(
            id = id,
            coord = featureCentroid,
            latlonCoord = latlonCoord,
            buildings = cellBuildingLists[i]
        )

        grid.add(cell)
        simpleTAZs.add(createSimpleTAZFromCell(cell))

        id += 1

    }

    // Buffer Area Cells

    // Get cluster in buffer area with gradually declining resolution
    val bufferBuildings = buildings.filter { !it.inFocusArea }

    // Boundary of focus area
    val faBoundary = geometryFactory.createMultiPoint(
        focusAreaBuildings.map { it.point }.toTypedArray()
    ).convexHull()

    var bufferCells = getBufferBuildingClusters(focusAreaPrecision = 5000.0,
        focusAreaBoundary = faBoundary, bufferBuildings = bufferBuildings, geometryFactory = geometryFactory, transformer = transformer, startID = id)

    for (c in bufferCells) {
        grid.add(c)
        simpleTAZs.add(createSimpleTAZFromCell(c))
    }

    // Cache Cells with Geometry and Building areas
    if (true)
    {
        val cellsOut = File("output_cells.json")
        FileOutputStream(cellsOut).use { f ->
            Json.encodeToStream(simpleTAZs, f)
        }
    }

    return grid.toList()

}

fun createSimpleTAZFromCell(cell : Cell) : OutputSimpleTAZ {
    return OutputSimpleTAZ(
        id = cell.id,

        lat = cell.latlonCoord.x,
        lon = cell.latlonCoord.y,

        //geometry = z,
        avgDistanceToSelf = (if(java.lang.Double.isNaN(cell.avgDistanceToSelf)) { 0.0 } else { cell.avgDistanceToSelf }) as Double,
        population = cell.population,
        inFocusArea = cell.inFocusArea,
        areaResidential = cell.areaResidential,
        areaCommercial = cell.areaCommercial,
        areaIndustrial = cell.areaIndustrial,
        areaOther = cell.areaOther
    )
}