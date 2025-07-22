package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.Building
import de.uniwuerzburg.omod.core.models.Cell
import de.uniwuerzburg.omod.utils.CRSTransformer
import org.apache.commons.math3.ml.clustering.CentroidCluster
import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer
import org.apache.commons.math3.ml.distance.DistanceMeasure
import org.apache.commons.math3.ml.distance.EuclideanDistance
import org.apache.commons.math3.random.JDKRandomGenerator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
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