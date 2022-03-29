package de.uniwuerzburg

import kotlinx.serialization.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope

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
            RESIDENTIAL -> 1.0
            INDUSTRIAL -> 2.0
            COMMERCIAL -> 2.0
            else -> 0.0
        }
    }
}

/**
 * Activity types.
 */
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;
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
data class Building(
    val coord: Coordinate,
    val area: Double,
    val population: Double,
    val landuse: Landuse,
    val regionType: Int
)

/**
 * Group of buildings. For faster 2D distribution sampling.
 * Method: Sample from Cells -> Sample from buildings in cell
 */
data class Cell (
    val population: Double,
    val priorWorkWeight: Double,
    val envelope: Envelope,
    val buildingIds: List<Int>,
    val featureCentroid: Coordinate,
    val regionType: Int
)

/**
 * Agent
 */
@Serializable
data class MobiAgent (
    val id: Int,
    val homogenousGroup: String,
    val mobilityGroup: String,
    val age: String,
    val home: Int,
    val work: Int,
    var profile: List<Activity>? = null
)

/**
 * Activity format in output.
 */
@Serializable
data class Activity (
    val type: ActivityType,
    val stayTime: Double?,
    val x: Double,
    val y: Double
)

