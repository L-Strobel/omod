package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.locationtech.jts.geom.Geometry

/**
 * OMOD result format of on activity
 */
@Serializable
data class OutputActivity (
    val type: ActivityType,
    val stayTime: Double?,
    val lat: Double,
    val lon: Double,
    val cellId: Int,
    val dummyLoc: Boolean,
    val inFocusArea: Boolean
)
/**
 * OMOD result format of o day
 */
@Serializable
data class OutputDiary (
    val day: Int,
    val dayType: Weekday,
    val activities: List<OutputActivity>
)
/**
 * OMOD result format of on agent
 */
@Serializable
data class OutputEntry (
    val id: Int,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: AgeGrp,
    val mobilityDemand: List<OutputDiary>
)

/**
 * Retrieves results from an agent and formats the data for output.
 *
 * @param agent Agent
 * @return Data ready for storage in json file
 */
fun formatOutput(agent: MobiAgent) : OutputEntry {
    val mobilityDemand = agent.mobilityDemand.map { dairy ->
        val activities = dairy.activities.map { activity ->
            OutputActivity(activity.type, activity.stayTime, activity.lat, activity.lon, activity.cellId,
                           activity.location is DummyLocation, activity.location.inFocusArea)
        }
        OutputDiary(dairy.day, dairy.dayType, activities)
    }
    return OutputEntry(agent.id, agent.homogenousGroup, agent.mobilityGroup, agent.age, mobilityDemand)
}

/**
 * Output format of trip
 */
@Serializable
data class OutputTrip (
    val distance: Double,   // Unit: Meter
    val time: Double,       // Unit: Second
    val lats: List<Double>?,
    val lons: List<Double>?,
    val isReal: Boolean
)
/**
 * Output format of day in assignment
 */
@Serializable
data class OutputTDiary (
    val day: Int,
    val trips: List<OutputTrip>
)
/**
 * Output format of all assignment information of an agent
 */
@Serializable
data class OutputTEntry (
    val id: Int,
    val days: List<OutputTDiary>
)




/**
 * SimpleTAZ: Cell with geometry and aggregated building areas.
 */
@Serializable
data class OutputSimpleTAZ(
    val id: Int,

    val lat: Double,
    val lon: Double,

    // @Contextual
    //val geometry: Geometry,

    val avgDistanceToSelf: Double,
    val inFocusArea: Boolean,
    val population: Double,

    val attractions: Map<Int, Double>
    /*
    val areaResidential: Double,
    val areaCommercial: Double,
    val areaIndustrial: Double,
    val areaOther: Double
     */
)