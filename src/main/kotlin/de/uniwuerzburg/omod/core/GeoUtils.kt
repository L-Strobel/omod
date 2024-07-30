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
import org.geotools.api.referencing.crs.ProjectedCRS
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.*

/**
 *  Globally used geometry factory
 */
val geometryFactory = GeometryFactory()

/**
 * Converts from lat lon coordinates to internally used coordinate system (UTM) and back.
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

    /**
     * Convert geometry to lat lon
     *
     * @param geometry Geometry to convert
     */
    fun toLatLon(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToLatLon)
    }

    /**
     * Convert geometry to internally used coordinate system (UTM)
     *
     * @param geometry Geometry to convert
     */
    fun toModelCRS(geometry: Geometry) : Geometry {
        return JTS.transform(geometry, transformerToUTM)
    }

    private fun getModelCRS(centerLon: Double): ProjectedCRS {
        val mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null)
        val factories = ReferencingFactoryContainer(null)

        val geoCRS = DefaultGeographicCRS.WGS84
        val cartCS = DefaultCartesianCS.GENERIC_2D

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
 * Separates a geometry into many envelopes. Used to quickly check if many smaller objects are within it.
 * Returns a set of envelopes that are either definitely within, definitely outside, or maybe inside the geometry.
 *
 * The algorithm works by creating a regular grid of the bounding box of the geometry and then testing each cell
 * individually. If an envelope is not completely within or disjoint from the geometry it is split into multiple
 * smaller envelopes. This process is repeated for all grid resolution defined in *resolutions*
 *
 * @param geometry Large geometry
 * @param resolutions Sizes of the regular grid that tested.
 * @param geometryFactory Geometry factory to use
 * @param ifNot Function that is applied to envelopes completely disjoint from the geometry
 * @param ifDoes Function that is applied to envelopes completely within the geometry
 * @param ifUnsure Function that is applied to envelopes that intersect the geometry but not completely
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
