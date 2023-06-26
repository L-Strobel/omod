package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.ActivityType
import de.uniwuerzburg.omod.core.Landuse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * GeoJSON Geometry
 */
@Serializable
sealed class GeoJsonGeom {
    /**
     * Transform the GeoJSON geometry to JTS geometry
     * @param factory Geometry factory
     * @return JTS geometry
     */
    abstract fun toJTS(factory: GeometryFactory) : Geometry
}

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
            factory.createPoint( Coordinate(coord[1], coord[0]) )
        }.toTypedArray()
        return factory.createMultiPoint(points)
    }
}

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

/**
 * GeoJSON geometry collection
 * @param geometries list of GeoJSON geometries
 */
@Serializable
@SerialName("GeometryCollection")
@Suppress("unused")
data class GeoJsonGeometryCollection(
    val geometries: List<GeoJsonGeom>
) : GeoJsonGeom(), GeoJsonNoProperties {
    override fun toJTS(factory: GeometryFactory): Geometry {
        val geoms = geometries.map { geom ->
            geom.toJTS(factory)
        }.toTypedArray()
        return factory.createGeometryCollection(geoms)
    }
}


/**
 * GeoJSON properties. Meta information about geometry.
 */
@Serializable
sealed class GeoJsonProperties ()

/**
 * GeoJSON representation of a building.
 *
 * @param osm_id OpenStreetMap ID
 * @param in_focus_area is the building inside the focus area?
 * @param area area of the building in meters
 * @param population population of building. Can be non-integer.
 * @param landuse OSM-Landuse of the building
 * @param number_shops Number of shops in the building
 * @param number_offices Number of offices in the building
 * @param number_schools Number of schools in the building
 * @param number_universities Number of universities in the building
 */
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

/**
 * GeoJSON representation of an origin-destination matrix entry.
 *
 * @param origin Name of the origin
 * @param origin_activity Activity conducted at origin
 * @param destination_activity Activity conducted at destination
 * @param destinations Key: Name of destination, Value: Number of trips going to destination
 */
@Serializable
@SerialName("ODEntry")
data class GeoJsonODProperties (
    val origin: String,
    val origin_activity: ActivityType,
    val destination_activity: ActivityType,
    val destinations: Map<String, Double>
) : GeoJsonProperties()

/**
 * GeoJSON representation of a census field.
 *
 * @param population Population of geometry
 */
@Serializable
@SerialName("CensusEntry")
data class GeoJsonCensusProperties (
    val population: Double
) : GeoJsonProperties()

/**
 * GeoJSON feature. Generic entry in a feature collection.
 *
 * @param type Type of feature
 * @param geometry Geometry of the feature
 * @param properties Other information about the feature
 */
@Serializable
data class GeoJsonFeature (
    val type: String = "Feature",
    val geometry: GeoJsonGeom,
    val properties: GeoJsonProperties
)

/**
 * GeoJSOn feature collection
 *
 * @param type Always "FeatureCollection"
 * @param features List of contained GeoJSON features
 */
@Serializable
data class GeoJsonFeatureCollection (
    val type: String = "FeatureCollection",
    val features: List<GeoJsonFeature>
)

/**
 * Work around structure for json objects with empty properties = {} and raw GeometryCollections
 */
@Serializable
sealed interface GeoJsonNoProperties

/**
 * Work around structure for json objects with empty properties = {} and raw GeometryCollections
 * See GeoJsonFeature.
 */
@Serializable
data class GeoJsonFeatureNoProperties (
    val type: String = "Feature",
    val geometry: GeoJsonGeom
)

/**
 * Work around structure for json objects with empty properties = {} and raw GeometryCollections
 * See GeoJsonFeatureCollection.
 */
@Serializable
@SerialName("FeatureCollection")
data class GeoJsonFeatureCollectionNoProperties (
    val features: List<GeoJsonFeatureNoProperties>
) : GeoJsonNoProperties