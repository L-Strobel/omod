package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON line string
 * @param coordinates list of Lon-Lat coordinates
 */
@Serializable
@SerialName("LineString")
@Suppress("unused")
data class GeoJsonLineString(
    val coordinates: List<List<Double>>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val coords = coordinates.map{ coord ->
            Coordinate(coord[1], coord[0])
        }.toTypedArray()
        return factory.createLineString(coords)
    }
}