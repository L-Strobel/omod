package de.uniwuerzburg

import kotlinx.serialization.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import java.io.BufferedReader
import java.io.FileReader

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
    val population: Double
    val workWeight: Double
    val nShops: Double
    val nSchools: Double
    val nUnis: Double
    val inFocusArea:  Boolean
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
    val latlonCoord: Coordinate,
    val area: Double,
    override val population: Double,
    val landuse: Landuse,
    val regionType: Int,
    override val nShops: Double,
    val nOffices: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val inFocusArea:  Boolean,
) : LocationOption {
    var taz: String? = null
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
    val buildingIds: List<Int>,
    val featureCentroid: Coordinate,
    val latlonCentroid: Coordinate,
    val regionType: Int,
    override val nShops: Double,
    val nOffices: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val inFocusArea:  Boolean
) : LocationOption {
    override val coord: Coordinate = featureCentroid
}

data class TAZ (
    override val coord: Coordinate,
    override val population: Double,
    override val workWeight: Double,
    override val nShops: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val inFocusArea:  Boolean
) : LocationOption

/**
 * Agent
 */
@Serializable
data class MobiAgent (
    val id: Int,
    val homogenousGroup: String,
    val mobilityGroup: String,
    val age: String,
    val homeID: Int,
    val workID: Int,
    val schoolID: Int,
    var profile: List<Activity>? = null
)

/**
 * Activity format in output.
 */
@Serializable
data class Activity (
    val type: ActivityType,
    val stayTime: Double?,
    val buildingID: Int,
    val lat: Double,
    val lon: Double
)

/**
 * Row in the OD-Matrix
 */
data class ODRow (
    val origin: String,
    val destinations: Map<String, Double>,
    val geometry: Polygon
)

class ODMatrix (odPath: String, factory: GeometryFactory) {
    val rows: Map<String, ODRow>

    init {
        // Read OD
        val reader = BufferedReader(FileReader(odPath))
        // Header
        val header = reader.readLine()
        val tazs = header.split(";").dropLast(1).drop(1)

        // Read body
        rows = mutableMapOf()
        reader.forEachLine {
            val line = it.split(";").toMutableList()

            val origin = line.removeFirst()

            val geometryStr = line.removeLast().trim('[', ']', '(', ')')
            val coordsStr = geometryStr.split("), (")
            val shell = coordsStr.map { coordStr ->
                val coordList = coordStr.split(", ")
                val lat = coordList.last().toDouble()
                val lon = coordList.first().toDouble() // TODO swap lat und lon in input csv
                latlonToMercator(lat, lon) // Transform the lat lons to cartesian coordinates
            }.toTypedArray()
            val geometry = factory.createPolygon(shell)

            val destinations = tazs.mapIndexed { i, taz -> taz to line[i].toDouble() }.toMap()

            rows[origin] = ODRow(origin, destinations, geometry)
        }
    }
}


