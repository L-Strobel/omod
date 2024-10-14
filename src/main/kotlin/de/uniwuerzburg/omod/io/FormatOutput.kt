package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.io.json.*
import java.time.LocalTime

/**
 * Retrieves results from an agent and formats the data for output.
 *
 * @param agent Agent
 * @return Data ready for storage in json file
 */
fun formatOutput(agent: MobiAgent) : OutputEntry {
    val mobilityDemand = agent.mobilityDemand.map { diary ->
        val legs = mutableListOf<OutputLeg>()
        var id = 0
        var currentTime = LocalTime.of(0, 0)
        for (i in 0 until diary.activities.size) {
            val activity = diary.activities[i]
            legs.add(
                OutputActivity(
                    id, activity.type, currentTime.toString(), activity.stayTime,
                    activity.location.latlonCoord.x, activity.location.latlonCoord.y,
                    activity.location is DummyLocation, activity.location.inFocusArea
                )
            )
            id += 1
            currentTime = currentTime.plusMinutes(activity.stayTime?.toLong() ?: 0)

            if (i >= diary.trips.size) { continue }
            val trip = diary.trips[i]
            legs.add(
                OutputTrip(
                    id, trip.mode, currentTime.toString(), trip.distance, trip.time, trip.lats, trip.lons
                )
            )
            id += 1
            currentTime = currentTime.plusMinutes(trip.time?.toLong() ?: 0)
        }
        OutputDiary(diary.day, diary.dayType, legs)
    }
    return OutputEntry(
        agent.id, agent.homogenousGroup, agent.mobilityGroup, agent.age, agent.sex, agent.carAccess, mobilityDemand
    )
}

