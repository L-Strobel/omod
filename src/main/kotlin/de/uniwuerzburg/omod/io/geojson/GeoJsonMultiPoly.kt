package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON multi polygon
 * @param coordinates list of coordinates of polygons
 */
@Serializable
@SerialName("MultiPolygon")
@Suppress("unused")
data class GeoJsonMultiPoly(
    val coordinates: List<List<List<List<Double>>>>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val polys = coordinates.map { polys ->
            val rings = polys.map { ring ->
                val coords = ring.map { coord -> Coordinate(coord[1], coord[0]) }
                factory.createLinearRing(coords.toTypedArray())
            }
            factory.createPolygon(rings.first(), rings.drop(1).toTypedArray())
        }
        return factory.createMultiPolygon(polys.toTypedArray())
    }
}