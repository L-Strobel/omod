package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON multi point
 * @param coordinates list of Lon-Lat coordinates of each point
 */
@Serializable
@SerialName("MultiPoint")
@Suppress("unused")
data class GeoJsonMultiPoint(
    val coordinates: List<List<Double>>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val points = coordinates.map{ coord ->
            factory.createPoint(Coordinate(coord[1], coord[0]))
        }.toTypedArray()
        return factory.createMultiPoint(points)
    }
}