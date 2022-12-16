package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.ActivityType
import de.uniwuerzburg.omod.core.Landuse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
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
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val coord = Coordinate(coordinates[1], coordinates[0])
        return factory.createPoint(coord)
    }
}

@Serializable
@SerialName("MultiPoint")
@Suppress("unused")
data class GeoJsonMultiPoint(
    val coordinates: List<List<Double>>
) : GeoJsonGeom() {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val points = coordinates.map{ coord ->
            factory.createPoint( Coordinate(coord[1], coord[0]) )
        }.toTypedArray()
        return factory.createMultiPoint(points)
    }
}

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

@Serializable
@SerialName("GeometryCollection")
@Suppress("unused")
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


// Expected meta information
@Serializable
sealed class GeoJsonProperties

@Serializable
@SerialName("BuildingEntree")
data class GeoJsonBuildingProperties (
    val osm_id: Long,
    val in_focus_area: Boolean,
    val area: Double,
    val population: Double?,
    val landuse: Landuse,
    val number_shops: Double,
    val number_offices: Double,
    val number_schools: Double,
    val number_universities: Double,
) : GeoJsonProperties()

@Serializable
@SerialName("ODEntry")
data class GeoJsonODProperties (
    val origin: String,
    val origin_activity: ActivityType,
    val destination_activity: ActivityType,
    val destinations: Map<String, Double>
) : GeoJsonProperties()

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
