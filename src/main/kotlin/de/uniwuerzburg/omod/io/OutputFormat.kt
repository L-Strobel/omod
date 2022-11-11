package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.ActivityType
import de.uniwuerzburg.omod.core.DummyLocation
import de.uniwuerzburg.omod.core.MobiAgent
import kotlinx.serialization.Serializable

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