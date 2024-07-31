package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON point
 * @param coordinates Lon-Lat coordinates
 */
@Serializable
@SerialName("Point")
data class GeoJsonPoint(
    val coordinates: List<Double>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val coord = Coordinate(coordinates[1], coordinates[0])
        return factory.createPoint(coord)
    }
}