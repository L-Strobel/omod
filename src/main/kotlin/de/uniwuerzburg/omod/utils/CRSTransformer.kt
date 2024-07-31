package de.uniwuerzburg.omod.utils

import org.geotools.api.referencing.crs.ProjectedCRS
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.ReferencingFactoryFinder
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.cs.DefaultCartesianCS
import org.geotools.referencing.factory.ReferencingFactoryContainer
import org.geotools.referencing.operation.DefaultCoordinateOperationFactory
import org.geotools.referencing.operation.projection.TransverseMercator
import org.locationtech.jts.geom.Geometry
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

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