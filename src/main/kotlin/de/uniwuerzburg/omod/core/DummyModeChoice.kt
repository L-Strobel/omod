package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.routing.RoutingCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.*
import kotlin.time.TimeSource

// TODO Distance often zero
class DummyModeChoice(
    private val routingCache: RoutingCache
): ModeChoice {
    override fun doModeChoice(
        agents: List<MobiAgent>, mainRng: Random, dispatcher: CoroutineDispatcher
    ) : List<MobiAgent> {
        val timeSource = TimeSource.Monotonic
        val timestampStartInit = timeSource.markNow()
        for (chunk in agents.chunked(10000)) { // Don't launch to many coroutines at once
            runBlocking(dispatcher) {
                for (agent in chunk) {
                    launch(dispatcher) {
                        for (diary in agent.mobilityDemand) {
                            diary.trips = getTrips(diary)
                        }
                    }
                }
            }
        }
        logger.info("Mode Choice took: ${timeSource.markNow() - timestampStartInit}")
        return  agents
    }

    private fun getTrips(diary: Diary) : List<Trip> {
        val trips = mutableListOf<Trip>()
        if (diary.activities.size <= 1) { return  trips } // No mobility that day

        // Run through day
        var currentActivity = diary.activities.first()
        for (nextActivity in diary.activities.drop(1)) {
            // Get car distance of trip
            val carDistance = routingCache.getDistances(
                currentActivity.location, listOf(nextActivity.location)
            ).first().toDouble() / 1000

            val trip = Trip(
                currentActivity.location,
                nextActivity.location,
                carDistance,
                null,
            )
            trips.add(trip)
        }
        return trips
    }
}