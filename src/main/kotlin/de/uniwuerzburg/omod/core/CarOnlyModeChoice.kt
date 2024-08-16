package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.routing.Route
import de.uniwuerzburg.omod.routing.routeFallback
import de.uniwuerzburg.omod.routing.routeWith
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.util.*
import kotlin.time.TimeSource

class CarOnlyModeChoice(
    private val hopper: GraphHopper, private val withPath: Boolean
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
                            diary.visitTrips( ::tripVisitor )
                        }
                    }
                }
            }
        }
        logger.info("Mode Choice took: ${timeSource.markNow() - timestampStartInit}")
        return  agents
    }

    private fun tripVisitor(
        trip: Trip, originActivity: Activity, destinationActivity: Activity,
        departureTime: LocalTime, departureWD: Weekday, finished: Boolean
    )  {
        val origin = originActivity.location
        val destination = destinationActivity.location

        val route = if ((origin is RealLocation) && (destination is RealLocation)) {
            val response  = routeWith("car", origin, destination, hopper)
            if (!response.hasErrors()) {
                Route.fromGHResponse(response, withPath)
            } else {
                routeFallback(Mode.CAR_DRIVER, origin, destination)
            }
        } else {
            routeFallback(Mode.CAR_DRIVER, origin, destination)
        }

        trip.mode = Mode.CAR_DRIVER
        trip.time = route.time
        trip.distance = route.distance
        trip.lats = route.lats
        trip.lons = route.lons
    }
}