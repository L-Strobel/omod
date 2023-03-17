package de.uniwuerzburg.omod.core

import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.ReferencingFactoryFinder
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.cs.DefaultCartesianCS
import org.geotools.referencing.factory.ReferencingFactoryContainer
import org.geotools.referencing.operation.DefaultCoordinateOperationFactory
import org.geotools.referencing.operation.projection.TransverseMercator
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.opengis.referencing.crs.GeographicCRS
import org.opengis.referencing.crs.ProjectedCRS
import org.opengis.referencing.cs.CartesianCS
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.*

// Global factory
val geometryFactory = GeometryFactory()

/**
 * Use this class to transform from lat lon to the CRS used in OMOD and back.
 * Currently, OMOD uses web mercator.
 */
class CRSTransformer (
    centerLon: Double
) {
    init {
        System.setProperty("hsqldb.reconfig_logging", "false") // Silence hsqldb
        Logger.getLogger("hsqldb.db").level = Level.WARNING
    }

    private val latlonCRS = CRS.decode("EPSG:4326")
    private val utmCRS = getModelCRS(centerLon)
    private val transformerToLatLon = CRS.findMathTransform(utmCRS, latlonCRS)
    private val transformerToUTM = CRS.findMathTransform(latlonCRS, utmCRS)

    fun toLatLon(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToLatLon)
    }

    fun toModelCRS(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToUTM)
    }

    private fun getModelCRS(centerLon: Double): ProjectedCRS {
        val mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null)
        val factories = ReferencingFactoryContainer(null)

        val geoCRS: GeographicCRS = DefaultGeographicCRS.WGS84
        val cartCS: CartesianCS = DefaultCartesianCS.GENERIC_2D

        val parameters = mtFactory.getDefaultParameters("Transverse_Mercator")
        parameters.parameter("central_meridian").setValue(centerLon)
        parameters.parameter("latitude_of_origin").setValue(0.0)
        parameters.parameter("scale_factor").setValue(0.9996)
        parameters.parameter("false_easting").setValue(500000.0)
        parameters.parameter("false_northing").setValue(0.0)

        val properties = Collections.singletonMap<String, Any>("name", "OMOD-Model-CRS")
        val conversion = DefaultCoordinateOperationFactory()
            .createDefiningConversion(properties, TransverseMercator.Provider(), parameters)
        return factories.crsFactory.createProjectedCRS(properties, geoCRS, conversion, cartCS)
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
