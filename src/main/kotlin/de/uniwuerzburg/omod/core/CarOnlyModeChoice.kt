package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.routing.Route
import de.uniwuerzburg.omod.routing.routeFallback
import de.uniwuerzburg.omod.routing.routeWith
import de.uniwuerzburg.omod.utils.ProgressBar
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

class CarOnlyModeChoice(
    private val hopper: GraphHopper, private val withPath: Boolean
): ModeChoice {
    override fun doModeChoice(
        agents: List<MobiAgent>, mainRng: Random, dispatcher: CoroutineDispatcher
    ) : List<MobiAgent> {
        val timeSource = TimeSource.Monotonic
        val timestampStartInit = timeSource.markNow()
        val jobsDone = AtomicInteger()
        val totalJobs = (agents.size).toDouble()

        for (chunk in agents.chunked(10000)) { // Don't launch to many coroutines at once
            runBlocking(dispatcher) {
                for (agent in chunk) {
                    launch(dispatcher) {
                        for (diary in agent.mobilityDemand) {
                            diary.visitTrips( ::tripVisitor )
                        }
                        val done = jobsDone.incrementAndGet()
                        print("Mode Choice: ${ProgressBar.show(done / totalJobs)}\r")
                    }
                }
            }
        }
        println("Mode Choice: " + ProgressBar.done())
        logger.info("Mode Choice took: ${timeSource.markNow() - timestampStartInit}")
        return  agents
    }

    private fun tripVisitor(
        trip: Trip, originActivity: Activity, destinationActivity: Activity,
        @Suppress("UNUSED_PARAMETER") departureTime: LocalTime,
        @Suppress("UNUSED_PARAMETER") departureWD: Weekday,
        @Suppress("UNUSED_PARAMETER") finished: Boolean
    )  {
        val route = Route.getWithFallback(
            Mode.CAR_DRIVER, originActivity.location, destinationActivity.location,
            hopper, withPath, null, null
        )

        trip.mode = Mode.CAR_DRIVER
        trip.time = route.time
        trip.distance = route.distance
        trip.lats = route.lats
        trip.lons = route.lons
    }
}