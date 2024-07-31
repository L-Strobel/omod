package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON Geometry
 */
@Serializable
sealed class GeoJsonGeom : GeoJsonNoProperties {
    /**
     * Transform the GeoJSON geometry to JTS geometry
     * @param factory Geometry factory
     * @return JTS geometry
     */
    abstract fun toJTS(factory: GeometryFactory) : Geometry
}