package de.uniwuerzburg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

@Serializable
sealed class GeoJsonGeom {
    abstract fun toJTS(factory: GeometryFactory) : Geometry
}

@Serializable
@SerialName("Polygon")
data class GeoJsonPoly(
    val coordinates: List<List<List<Double>>>
) : GeoJsonGeom () {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val rings = coordinates.map { ring ->
            val coords = ring.map { coord -> latlonToMercator(coord.first(), coord.last()) }
            factory.createLinearRing(coords.toTypedArray())
        }
        return factory.createPolygon(rings.first(), rings.drop(1).toTypedArray())
    }
}

@Serializable
@SerialName("MultiPolygon")
data class GeoJsonMultiPoly(
    val coordinates: List<List<List<List<Double>>>>
) : GeoJsonGeom () {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val polys = coordinates.map { polys ->
            val rings = polys.map { ring ->
                val coords = ring.map { coord -> latlonToMercator(coord.first(), coord.last()) }
                factory.createLinearRing(coords.toTypedArray())
            }
            factory.createPolygon(rings.first(), rings.drop(1).toTypedArray())
        }
        return factory.createMultiPolygon(polys.toTypedArray())
    }
}

@Serializable
data class GeoJsonProperties (
    val origin: String,
    val originActivity: ActivityType,
    val destinationActivity: ActivityType,
    val destinations: Map<String, Double>
)
@Serializable
data class GeoJsonFeature (
    val geometry: GeoJsonGeom,
    val properties: GeoJsonProperties
)
@Serializable
data class GeoJsonFeatureCollection (
    val features: List<GeoJsonFeature>
)