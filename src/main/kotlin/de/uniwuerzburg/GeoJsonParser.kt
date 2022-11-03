package de.uniwuerzburg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

// Expected geometries
@Serializable
sealed class GeoJsonGeom {
    abstract fun toJTS(factory: GeometryFactory) : Geometry
}

@Serializable
@SerialName("Point")
data class GeoJsonPoint(
    val coordinates: List<Double>
) : GeoJsonGeom () {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val coord = latlonToMercator(coordinates[1], coordinates[0])
        return factory.createPoint(coord)
    }
}

@Serializable
@SerialName("Polygon")
@Suppress("unused")
data class GeoJsonPoly(
    val coordinates: List<List<List<Double>>>
) : GeoJsonGeom () {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val rings = coordinates.map { ring ->
            val coords = ring.map { coord -> latlonToMercator(coord[1], coord[0]) }
            factory.createLinearRing(coords.toTypedArray())
        }
        return factory.createPolygon(rings.first(), rings.drop(1).toTypedArray())
    }
}

@Serializable
@SerialName("MultiPolygon")
@Suppress("unused")
data class GeoJsonMultiPoly(
    val coordinates: List<List<List<List<Double>>>>
) : GeoJsonGeom () {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val polys = coordinates.map { polys ->
            val rings = polys.map { ring ->
                val coords = ring.map { coord -> latlonToMercator(coord[1], coord[0]) }
                factory.createLinearRing(coords.toTypedArray())
            }
            factory.createPolygon(rings.first(), rings.drop(1).toTypedArray())
        }
        return factory.createMultiPolygon(polys.toTypedArray())
    }
}

// Expected meta information
@Serializable
sealed class GeoJsonProperties

@Serializable
@SerialName("BuildingEntree")
data class GeoJsonBuildingProperties (
    val osm_id: Int,
    val in_focus_area: Boolean,
    val area: Double,
    val population: Double?,
    val landuse: String,
    val number_shops: Double,
    val number_offices: Double,
    val number_schools: Double,
    val number_universities: Double,
) : GeoJsonProperties ()

@Serializable
@SerialName("ODEntry")
data class GeoJsonODProperties (
    val origin: String,
    val origin_activity: ActivityType,
    val destination_activity: ActivityType,
    val destinations: Map<String, Double>
) : GeoJsonProperties ()

@Serializable
@SerialName("CensusEntry")
data class GeoJsonCensusProperties (
    val population: Double
) : GeoJsonProperties()

// Basic structure
@Serializable
data class GeoJsonFeature (
    val type: String = "Feature",
    val geometry: GeoJsonGeom,
    val properties: GeoJsonProperties
)

@Serializable
data class GeoJsonFeatureCollection (
    val type: String = "FeatureCollection",
    val features: List<GeoJsonFeature>
)
