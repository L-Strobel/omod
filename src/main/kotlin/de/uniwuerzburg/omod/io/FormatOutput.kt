package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.io.json.*

/**
 * Retrieves results from an agent and formats the data for output.
 *
 * @param agent Agent
 * @return Data ready for storage in json file
 */
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

