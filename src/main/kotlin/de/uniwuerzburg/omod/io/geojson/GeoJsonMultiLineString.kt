package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON multi line string
 * @param coordinates list of line string coordinates
 */
@Serializable
@SerialName("MultiLineString")
@Suppress("unused")
data class GeoJsonMultiLineString(
    val coordinates: List<List<List<Double>>>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val lines = coordinates.map{ line ->
            val coords = line.map{ coord ->
                Coordinate(coord[1], coord[0])
            }
            factory.createLineString(coords.toTypedArray())
        }.toTypedArray()
        return factory.createMultiLineString(lines)
    }
}