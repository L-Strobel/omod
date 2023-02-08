package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.*
import kotlinx.serialization.Serializable

/**
 * Output format mobility demand
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
data class OutputDiary (
    val day: Int,
    val dayType: Weekday,
    val activities: List<OutputActivity>
)

@Serializable
data class OutputEntry (
    val id: Int,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: AgeGrp,
    val mobilityDemand: List<OutputDiary>
)

fun formatOutput(agent: MobiAgent) : OutputEntry {
    val mobilityDemand = agent.mobilityDemand.map { dairy ->
        val activities = dairy.activities.map { activity ->
            OutputActivity(activity.type, activity.stayTime, activity.lat, activity.lon,
                           activity.location is DummyLocation, activity.location.inFocusArea)
        }
        OutputDiary(dairy.day, dairy.dayType, activities)
    }
    return OutputEntry(agent.id, agent.homogenousGroup, agent.mobilityGroup, agent.age, mobilityDemand)
}

/**
 * Output format assignment
 */
@Serializable
data class OutputTrip (
    val distance: Double,   // Unit: Meter
    val time: Double,       // Unit: Second
    val lats: List<Double>?,
    val lons: List<Double>?,
    val isReal: Boolean
)

@Serializable
data class OutputTDiary (
    val day: Int,
    val activities: List<OutputTrip>
)

@Serializable
data class OutputTEntry (
    val id: Int,
    val days: List<OutputTDiary>
)
