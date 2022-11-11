package de.uniwuerzburg.omod.core

/**
    Possible days. TODO: Should be enumeration
 */
val weekdays = listOf("mo", "tu", "we", "th", "fr", "sa", "so")

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