package de.uniwuerzburg

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import java.awt.Taskbar.Feature
import java.io.BufferedReader
import java.io.FileReader
import java.util.Properties

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import org.locationtech.jts.geom.Geometry
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
    val population: Double
    val workWeight: Double
    val nShops: Double
    val nSchools: Double
    val nUnis: Double
    val regionType: Int
    val inFocusArea:  Boolean
    var taz: String?
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
    val id: Int,
    val osmID: Int,
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    val area: Double,
    override val population: Double,
    val landuse: Landuse,
    override val regionType: Int,
    override val nShops: Double,
    val nOffices: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val inFocusArea:  Boolean,
) : LocationOption {
    override var taz: String? = null
    override val workWeight: Double = nShops + nOffices + landuse.getWorkWeight()
}

/**
 * Group of buildings. For faster 2D distribution sampling.
 * Method: Sample from Cells -> Sample from buildings in cell
 */
data class Cell (
    override val population: Double,
    override val workWeight: Double,
    val envelope: Envelope,
    val buildings: List<Building>,
    val featureCentroid: Coordinate,
    val latlonCentroid: Coordinate,
    override val regionType: Int,
    override val nShops: Double,
    val nOffices: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val inFocusArea:  Boolean,
    override var taz: String?,
) : LocationOption {
    override val coord: Coordinate = featureCentroid
    override val latlonCoord: Coordinate = mercatorToLatLon(coord.x, coord.y)
}

data class TAZ (
    override val coord: Coordinate,
    override val population: Double,
    override val workWeight: Double,
    override val nShops: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val regionType: Int,
    override val inFocusArea:  Boolean,
    override var taz: String?
) : LocationOption {
    override val latlonCoord: Coordinate = mercatorToLatLon(coord.x, coord.y)
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
    val building: LocationOption,
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
    val lon: Double
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
    val profile = agent.profile?.map { OutputActivity(it.type, it.stayTime, it.lat, it.lon) }
    return OutputEntry(agent.id, agent.homogenousGroup, agent.mobilityGroup, agent.age, profile)
}

/**
 * Row in the OD-Matrix
 */
data class ODRow (
    val origin: String,
    val destinations: Map<String, Double>,
    val geometry: Geometry
)

class ODMatrix (odPath: String, factory: GeometryFactory) {
    val rows: Map<String, ODRow>

    init {
        rows = mutableMapOf()

        // Read OD
        val geoJson: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
            .decodeFromString(File(odPath).readText(Charsets.UTF_8))

        for (entry in geoJson.features) {
            val origin = entry.properties.origin
            val geometry = entry.geometry.toJTS(factory)
            val destinations = entry.properties.destinations
            rows[origin] = ODRow(origin, destinations, geometry)
        }
    }
}


