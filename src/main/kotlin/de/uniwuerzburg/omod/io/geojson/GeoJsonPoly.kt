package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON polygon
 * @param coordinates first list -> coordinates of closed line string that defines exterior, other lists -> holes
 */
@Serializable
@SerialName("Polygon")
@Suppress("unused")
data class GeoJsonPoly(
    val coordinates: List<List<List<Double>>>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val rings = coordinates.map { ring ->
            val coords = ring.map { coord -> Coordinate(coord[1], coord[0]) }
            factory.createLinearRing(coords.toTypedArray())
        }
        return factory.createPolygon(rings.first(), rings.drop(1).toTypedArray())
    }
}