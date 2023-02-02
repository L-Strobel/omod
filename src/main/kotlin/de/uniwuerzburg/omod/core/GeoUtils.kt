package de.uniwuerzburg.omod.core

import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.opengis.referencing.crs.CoordinateReferenceSystem
import org.opengis.referencing.operation.MathTransform
import kotlin.math.*



/**
 * Use this class to transform from lat lon to the CRS used in OMOD and back.
 * Currently, OMOD uses web mercator.
 */

object CRSTransformer {
    private val latlonCRS: CoordinateReferenceSystem
    private val mercatorCRS: CoordinateReferenceSystem
    private val transformerToLatLon: MathTransform
    private val transformerToUTM: MathTransform

    init {
        // Get CRS
        val latlonWKT = {}.javaClass.classLoader.getResource("EPSG_4326.wkt")!!.readText(Charsets.UTF_8)
        latlonCRS = CRS.parseWKT(latlonWKT)
        val mercatorWKT = {}.javaClass.classLoader.getResource("EPSG_3857.wkt")!!.readText(Charsets.UTF_8)
        mercatorCRS = CRS.parseWKT(mercatorWKT)
        // Get transformations
        transformerToLatLon = CRS.findMathTransform(mercatorCRS, latlonCRS)
        transformerToUTM = CRS.findMathTransform(latlonCRS, mercatorCRS)
    }

    fun toLatLon(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToLatLon)
    }

    fun toModelCRS(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToUTM)
    }
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
