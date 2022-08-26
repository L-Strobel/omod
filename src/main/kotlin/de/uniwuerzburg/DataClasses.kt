package de.uniwuerzburg

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import java.io.File

/**
 * Definition of the agent population in terms of sociodemographic features
 */
@Serializable
data class PopulationDef (
    val homogenousGroup: Map<String, Double>,
    val mobilityGroup: Map<String, Double>,
    val age: Map<String, Double>
)
fun PopulationDef(map: Map<String, Map<String, Double>>): PopulationDef{
    require(map.containsKey("homogenousGroup"))
    require(map.containsKey("mobilityGroup"))
    require(map.containsKey("age"))

    return PopulationDef(map["homogenousGroup"]!!, map["mobilityGroup"]!!, map["age"]!!)
}

/**
 * Distance distributions from MID 2017
 */
@Serializable
data class DistanceDistributions(
    val home_work: Map<Int, Distribution>,
    val home_school: Map<Int, Distribution>,
    val any_shopping: Map<Int, Distribution>,
    val any_other: Map<Int, Distribution>
) {
    @Serializable
    data class Distribution(
        val distribution: String,
        val shape: Double,
        val scale: Double
    )
}

/**
 * Landuse categories. For work and home probabilities
 */
enum class Landuse {
    RESIDENTIAL, INDUSTRIAL, COMMERCIAL, RECREATIONAL, AGRICULTURE, FOREST, NONE;

    fun getWorkWeight() : Double {
        return when(this) {
            RESIDENTIAL -> 0.0
            INDUSTRIAL -> 1.0
            COMMERCIAL -> 0.0
            else -> 0.0
        }
    }
}

/**
 * Activity types.
 */
@Suppress("unused")
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;
}

/**
 * Interface for cells and buildings
 */
interface LocationOption {
    val coord: Coordinate
    val latlonCoord: Coordinate
    val homeWeight: Double
    val workWeight: Double
    val schoolWeight: Double
    val shoppingWeight: Double
    val otherWeight: Double
    val regionType: Int
    var taz: String?
    val avgDistanceToSelf: Double
    val inFocusArea: Boolean
}

/**
 * Model for a building
 *
 * @param coord coordinates in meters
 * @param area area of the building in meters
 * @param population population of building. Can be non-integer.
 * @param landuse OSM-Landuse of the building
 * @param regionType RegioStar7 of the municipality
 */
data class Building  (
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override val regionType: Int,
    override var taz: String?,
    override val inFocusArea: Boolean,

    val point: Point,

    val osmID: Int,
    val area: Double,
    val population: Double?,
    val landuse: Landuse,
    val nShops: Double,
    val nOffices: Double,
    val nSchools: Double,
    val nUnis: Double,
) : LocationOption {
    override val workWeight = nShops + nOffices + landuse.getWorkWeight()
    override val homeWeight = population ?: 1.0
    override val schoolWeight = nSchools
    override val shoppingWeight = nShops
    override val otherWeight = 1.0
    override val avgDistanceToSelf = 0.0

    companion object {
        fun fromGeoJson(collection: GeoJsonFeatureCollection, geometryFactory: GeometryFactory): List<Building> {
            return collection.features.map {
                require(it.properties is GeoJsonBuildingProperties) {
                    "Geo json contains features that are not buildings!"
                }

                val properties = it.properties
                val point = it.geometry.toJTS(geometryFactory).centroid

                Building(
                    osmID = properties.osm_id,
                    coord = point.coordinate,
                    latlonCoord = mercatorToLatLon(point.coordinate.x, point.coordinate.y),
                    area = properties.area,
                    population = properties.population,
                    landuse = Landuse.valueOf(properties.landuse),
                    regionType = properties.region_type,
                    nShops = properties.number_shops,
                    nOffices = properties.number_offices,
                    nSchools = properties.number_schools,
                    nUnis = properties.number_universities,
                    inFocusArea = properties.in_focus_area,
                    taz = null,
                    point = point
                )
            }
        }
    }
}


/**
 * Group of buildings. For faster 2D distribution sampling.
 * Method: Sample from Cells -> Sample from buildings in cell
 */
data class Cell (
    override val coord: Coordinate,

    val envelope: Envelope,
    val buildings: List<Building>,
) : LocationOption {
    override val avgDistanceToSelf = buildings.map { it.coord.distance(coord) }.average()

    override val latlonCoord: Coordinate = mercatorToLatLon(coord.x, coord.y)

    // Most common region type
    override val regionType = buildings.groupingBy { it.regionType }.eachCount().maxByOrNull { it.value }!!.key

    // Most common taz (Normally null here)
    override var taz = buildings.groupingBy { it.taz }.eachCount().maxByOrNull { it.value }!!.key

    // Sum
    override val homeWeight = buildings.sumOf { it.homeWeight }
    override val workWeight = buildings.sumOf { it.workWeight }
    override val schoolWeight = buildings.sumOf { it.schoolWeight }
    override val shoppingWeight = buildings.sumOf { it.shoppingWeight }
    override val otherWeight = buildings.sumOf { it.otherWeight }

    override val inFocusArea = buildings.any { it.inFocusArea }
}

/**
 * Dummy location for far away locations. Used for commuting flows.
 */
data class DummyLocation (
    override val coord: Coordinate,
    override val homeWeight: Double,
    override val workWeight: Double,
    override val schoolWeight: Double,
    override val shoppingWeight: Double,
    override val otherWeight: Double,
    override var taz: String?
) : LocationOption {
    override val regionType = 0 // 0 means undefined
    override val avgDistanceToSelf = 1.0 // Number irrelevant as long as it is > 0
    override val latlonCoord = mercatorToLatLon(coord.x, coord.y)
    override val inFocusArea = false
}

/**
 * Agent
 */
data class MobiAgent (
    val id: Int,
    val homogenousGroup: String,
    val mobilityGroup: String,
    val age: String,
    val home: LocationOption,
    val work: LocationOption,
    val school: LocationOption,
    var profile: List<Activity>? = null
)

/**
 * Activity
 */
data class Activity (
    val type: ActivityType,
    val stayTime: Double?,
    val location: LocationOption,
    val lat: Double,
    val lon: Double
)

/**
 * Output format
 */
@Serializable
data class OutputActivity (
    val type: ActivityType,
    val stayTime: Double?,
    val lat: Double,
    val lon: Double,
    val dummyLoc: Boolean,
    val inFocusArea: Boolean
)
@Serializable
data class OutputEntry (
    val id: Int,
    val homogenousGroup: String,
    val mobilityGroup: String,
    val age: String,
    val profile: List<OutputActivity>?
)
fun formatOutput(agent: MobiAgent) : OutputEntry {
    val profile = agent.profile?.map {
        OutputActivity(it.type, it.stayTime, it.lat, it.lon, it.location is DummyLocation, it.location.inFocusArea)
    }
    return OutputEntry(agent.id, agent.homogenousGroup, agent.mobilityGroup, agent.age, profile)
}

/**
 * Row in the OD-Matrix
 */
data class ODRow (
    val origin: String,
    val originActivity: ActivityType,
    val destinationActivity: ActivityType,
    val destinations: Map<String, Double>,
    val geometry: Geometry
)

class ODMatrix (odFile: File, factory: GeometryFactory) {
    val rows: Map<String, ODRow>

    init {
        rows = mutableMapOf()

        // Read OD
        val geoJson: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
            .decodeFromString(odFile.readText(Charsets.UTF_8))

        for (entry in geoJson.features) {
            val properties = entry.properties as GeoJsonODProperties
            val origin = properties.origin
            val geometry = entry.geometry.toJTS(factory)
            val originActivity = properties.origin_activity
            val destinationActivity = properties.destination_activity
            val destinations = properties.destinations

            rows[origin] = ODRow(origin, originActivity, destinationActivity, destinations, geometry)
        }
    }
}


