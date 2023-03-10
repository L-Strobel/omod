package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.routing.calcDistanceBeeline
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer
import org.apache.commons.math3.ml.distance.EuclideanDistance
import org.apache.commons.math3.random.JDKRandomGenerator
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import org.locationtech.jts.index.kdtree.KdTree
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.*

// Global factory
val geometryFactory = GeometryFactory()

/**
 * Use this class to transform from lat lon to the CRS used in OMOD and back.
 * Currently, OMOD uses web mercator.
 */
object CRSTransformer {
    init {
        System.setProperty("hsqldb.reconfig_logging", "false") // Silence hsqldb
        Logger.getLogger("hsqldb.db").level = Level.WARNING
    }

    private val latlonCRS = CRS.decode("EPSG:4326")
    private val utmCRS = CRS.decode("EPSG:3857")
    private val transformerToLatLon = CRS.findMathTransform(utmCRS, latlonCRS)
    private val transformerToUTM = CRS.findMathTransform(latlonCRS, utmCRS)

    fun toLatLon(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToLatLon)
    }

    fun toModelCRS(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToUTM)
    }
}

/**
 * Group buildings with a regular grid for faster sampling.
 */
fun makeGrid(gridResolution: Double,  buildings: List<Building>, geometryFactory: GeometryFactory,
             transformer: CRSTransformer) : List<Cell> {
    // Create KD-Tree for faster access
    val kdTree = KdTree()
    buildings.forEach { building ->
        kdTree.insert(building.coord, building)
    }

    val grid = mutableListOf<Cell>()
    val xMin = buildings.minOfOrNull { it.coord.x } ?:0.0
    val yMin = buildings.minOfOrNull { it.coord.y } ?:0.0
    val xMax = buildings.maxOfOrNull { it.coord.x } ?:0.0
    val yMax = buildings.maxOfOrNull { it.coord.y } ?:0.0

    var id = 0
    val distances = mutableListOf<Double>()
    for (x in semiOpenDoubleRange(xMin, xMax, gridResolution)) {
        for (y in semiOpenDoubleRange(yMin, yMax, gridResolution)) {
            val envelope = Envelope(x, x+gridResolution, y, y+gridResolution)
            val cellBuildings = kdTree.query(envelope).map { ((it as KdNode).data as Building) }
            if (cellBuildings.isEmpty()) continue

            // Centroid of all contained buildings
            val featureCentroid = geometryFactory.createMultiPoint(
                cellBuildings.map { it.point }.toTypedArray()
            ).centroid.coordinate

            val latlonCoord = transformer.toLatLon( geometryFactory.createPoint(featureCentroid) ).coordinate

            val cell = Cell(
                id = id,
                coord = featureCentroid,
                latlonCoord = latlonCoord,
                buildings = cellBuildings,
            )
            for (b in cellBuildings) {
                distances.add(calcDistanceBeeline(cell, b))
            }
            grid.add(cell)
            id += 1
        }
    }
    print(distances.average())
    return grid.toList()
}

fun cluster(nBuildingsPerCluster: Int,  buildings: List<Building>, geometryFactory: GeometryFactory,
            transformer: CRSTransformer
) : List<Cell> {
    val clusterer = KMeansPlusPlusClusterer<Building>(
        buildings.size / nBuildingsPerCluster,
        3,
        EuclideanDistance(),
        JDKRandomGenerator(1) // Fixed seed ensures the cells in a run are the same as in the cached routing data.
    )

    val centroids = clusterer.cluster(buildings)
    var id = 0
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
 * Group buildings with k-Means clustering for faster sampling
 *
 * Not used right now because it takes to long for an unbuffered area.
 */
@Suppress("unused")
fun makeClusterGrid(nBuildingsPerCluster: Int,  buildings: List<Building>, geometryFactory: GeometryFactory,
                    transformer: CRSTransformer
) : List<Cell> {
    // Get cluster in focus area
    val focusAreaBuildings = buildings.filter { it.inFocusArea }
    val cells = cluster(nBuildingsPerCluster, buildings.filter { it.inFocusArea }, geometryFactory, transformer)
        .toMutableList()

    // Get cluster in buffer area with gradually declining resolution
    val bufferBuildings = buildings.filter { !it.inFocusArea }

    // Boundary of focus area
    val faBoundary = geometryFactory.createMultiPoint(
        focusAreaBuildings.map { it.point }.toTypedArray()
    ).boundary

    // Determine distance to focus area and level of detail
    val buildingGroups = mutableMapOf<Int, MutableList<Building>>()
    for (building in bufferBuildings) {
        val distance = faBoundary.distance(building.point).toInt()

        // Quadratic decrease by distance of number of clusters in 5km chunks
        val nClusterDivisor = (distance / 5000 + 1).toDouble().pow(2).toInt()

        if(buildingGroups.containsKey(nClusterDivisor)) {
            buildingGroups[nClusterDivisor]!!.add(building)
        } else {
            buildingGroups[nClusterDivisor] = mutableListOf(building)
        }
    }

    for ((nClusterDivisor, buildingsAtDistance) in buildingGroups) {
        cells.addAll(
            cluster(
                nBuildingsPerCluster*nClusterDivisor, buildingsAtDistance,
                geometryFactory, transformer)
        )
    }
    return cells.toList()
}

/**
 * Find envelops that are covered by a geometry quickly
 */
fun fastCovers(geometry: Geometry, resolutions: List<Double>, geometryFactory: GeometryFactory,
               ifNot: (Envelope) -> Unit, ifDoes: (Envelope) -> Unit, ifUnsure: (Envelope) -> Unit) {
    val envelope = geometry.envelopeInternal!!
    val resolution = resolutions.first()
    for (x in semiOpenDoubleRange(envelope.minX, envelope.maxX, resolution)) {
        for (y in semiOpenDoubleRange(envelope.minY, envelope.maxY, resolution)) {
            val smallEnvelope = Envelope(x, min(x + resolution, envelope.maxX), y, min(y + resolution, envelope.maxY))
            val smallGeom = geometryFactory.toGeometry(smallEnvelope)

            if (geometry.disjoint(smallGeom)) {
                ifNot(smallEnvelope)
            } else if (geometry.contains(smallGeom)) {
                ifDoes(smallEnvelope)
            } else {
                val newResolutions = resolutions.drop(1)
                if (newResolutions.isEmpty()) {
                    ifUnsure(smallEnvelope)
                } else {
                    fastCovers(geometry.intersection(smallGeom), newResolutions, geometryFactory,
                        ifNot = ifNot, ifDoes = ifDoes,  ifUnsure = ifUnsure)
                }
            }
        }
    }
}
