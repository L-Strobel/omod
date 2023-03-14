package de.uniwuerzburg.omod.core

import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
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
    //private val utmCRS = CRS.decode("AUTO:42001,11.07868,49.45313")
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
/* fun makeGrid(gridResolution: Double,  buildings: List<Building>, geometryFactory: GeometryFactory,
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

            grid.add(cell)
            id += 1
        }
    }
    return grid.toList()
} */

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
