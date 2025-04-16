package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.routing.Route
import de.uniwuerzburg.omod.utils.ProgressBar
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/**
 * Turn every trip into a char trip.
 *
 * @param hopper GraphHopper for routing
 * @param withPath Return the lat-lon coordinates of the car trips.
 */
class ModeChoiceCarOnly(
    private val hopper: GraphHopper, private val withPath: Boolean
): ModeChoice {
    /**
     * Determine the mode of each trip and calculate the distance and time.
     *
     * @param agents Agents with trips (usually the trips have an UNDEFINED mode at this point)
     * @param mainRng Random number generator of the main thread
     * @param dispatcher Coroutine dispatcher used for concurrency
     * @return agents. Now their trips have specified modes.
     */
    override fun doModeChoice(
        agents: List<MobiAgent>, mainRng: Random, dispatcher: CoroutineDispatcher, modeSpeedUp: Map<Mode, Double>
    ) : List<MobiAgent> {
        val timeSource = TimeSource.Monotonic
        val timestampStartInit = timeSource.markNow()
        val jobsDone = AtomicInteger()
        val totalJobs = (agents.size).toDouble()

        for (chunk in agents.chunked(AppConstants.nAllowedCoroutines)) { // Don't launch to many coroutines at once
            runBlocking(dispatcher) {
                for (agent in chunk) {
                    val coroutineRng = Random(mainRng.nextLong())
                    launch(dispatcher) {
                        for (diary in agent.mobilityDemand) {
                            tripsToCar(diary, coroutineRng, modeSpeedUp)
                        }
                        val done = jobsDone.incrementAndGet()
                        print("Mode Choice: ${ProgressBar.show(done / totalJobs)}\r")
                    }
                }
            }
        }
        println("Mode Choice: " + ProgressBar.done())
        logger.info("Mode Choice took: ${timeSource.markNow() - timestampStartInit}")
        return agents
    }

    /**
     * Set all trips in a diary to car trips and route them,
     *
     * @param diary Mobility pattern on a day
     * @param rng Random number generator used in the thread.
     */
    private fun tripsToCar(diary: Diary, rng: Random, modeSpeedUp: Map<Mode, Double>) {
        val visitor = {
                trip: Trip, originActivity: Activity, destinationActivity: Activity,
                _: LocalTime, _: Weekday, _: Boolean ->
            val route = if (
                    (originActivity.type == destinationActivity.type) &&
                    (
                        (originActivity.type == ActivityType.HOME) ||
                        (originActivity.type == ActivityType.WORK) ||
                        (originActivity.type == ActivityType.SCHOOL)
                    )
                ) {
                // IF trip is from fixed location to same fixed location. Impute a randomly sampled Round-trip.
                val rtDistance = Route.sampleDistanceRoundTrip(originActivity.type, rng)
                Route.getRoundTripRoute(Mode.CAR_DRIVER, rtDistance)
            } else {
                Route.getWithFallback(
                    Mode.CAR_DRIVER, originActivity.location, destinationActivity.location,
                    hopper, withPath, null, null
                )
            }

            trip.mode = Mode.CAR_DRIVER
            trip.time = route.time / modeSpeedUp.getOrDefault(Mode.CAR_DRIVER, 1.0)
            trip.distance = route.distance
            trip.lats = route.lats
            trip.lons = route.lons
        }

        diary.visitTrips(visitor)
    }
}