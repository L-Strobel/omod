package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON geometry collection
 * @param geometries list of GeoJSON geometries
 */
@Serializable
@SerialName("GeometryCollection")
data class GeoJsonGeometryCollection(
    val geometries: List<GeoJsonGeom>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val geoms = geometries.map { geom ->
            geom.toJTS(factory)
        }.toTypedArray()
        return factory.createGeometryCollection(geoms)
    }
}