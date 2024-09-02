package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import com.graphhopper.gtfs.PtRouter
import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.io.json.readJsonFromResource
import de.uniwuerzburg.omod.routing.*
import de.uniwuerzburg.omod.utils.ProgressBar
import de.uniwuerzburg.omod.utils.createCumDist
import de.uniwuerzburg.omod.utils.sampleCumDist
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalTime
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.time.TimeSource

/**
 * Do mode choice with the mode options: FOOT, BICYCLE, CAR_DRIVER, CAR_PASSENGER, PUBLIC_TRANSIT
 * Uses a multinomial logit model at its core.
 *
 * @param hopper GraphHopper for routing
 * @param ptRouter GTFS router
 * @param ptSimDays Maps simulated weekday to a real date in the GTFS data.
 * @param timeZone Time zone of GTFS information. Necessary because GraphHopper works with time zone aware time.
 * @param withPath Return the lat-lon coordinates of the car trips.
 */
class GTFSModeChoice(
    private val hopper: GraphHopper,
    private val ptRouter: PtRouter,
    private val ptSimDays: Map<Weekday, LocalDate>,
    private val timeZone: TimeZone,
    private val withPath: Boolean
) : ModeChoice {
    private val tourModeOptions: Array<ModeUtility> = readJsonFromResource("tourModeUtilities.json")
    private val tripModeOptions: Array<ModeUtility> = readJsonFromResource("tripModeUtilities.json")

    /**
     * Determine the mode of each trip and calculate the distance and time.
     *
     * @param agents Agents with trips (usually the trips have an UNDEFINED mode at this point)
     * @param mainRng Random number generator of the main thread
     * @param dispatcher Coroutine dispatcher used for concurrency
     * @return agents. Now their trips have specified modes.
     */
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
                    val coroutineRng = Random(mainRng.nextLong())
                    launch(dispatcher) {
                        doModeChoiceFor(agent, coroutineRng)
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
     * Run through day and get all HOME-HOME tours. If the day does not start with a HOME activity,
     * all the trips before the first HOME activity are counted as a tour. The same is true for all the trips after the
     * last HOME activity if the day does not end with a HOME activity.
     *
     * @param diary Mobility pattern on a given day
     * @param rng Random number generator
     * @return Tours
     */
    private fun getTours(diary: Diary, rng: Random) : List<List<TripMCFeatures>> {
        val tours = mutableListOf<List<TripMCFeatures>>()
        var currentTour = mutableListOf<TripMCFeatures>()

        val visitor = {
            trip: Trip, originActivity: Activity, destinationActivity: Activity,
            departureTime: LocalTime, wd: Weekday, finished: Boolean ->
            val departureInstant = ptSimDays[wd]!!.atTime(departureTime).atZone(timeZone.toZoneId()).toInstant()

            // Routes for possible trips
            val rtDistances = mapOf(
                ActivityType.HOME to Route.sampleDistanceRoundTrip(ActivityType.HOME, rng),
                ActivityType.WORK to Route.sampleDistanceRoundTrip(ActivityType.WORK, rng),
                ActivityType.SCHOOL to Route.sampleDistanceRoundTrip(ActivityType.SCHOOL, rng)
            )

            // Get car distance and routes per mode
            val (carDistance, routes) = if (
                (originActivity.type == destinationActivity.type) &&
                (
                    (originActivity.type == ActivityType.HOME) ||
                    (originActivity.type == ActivityType.WORK) ||
                    (originActivity.type == ActivityType.SCHOOL)
                )
            ) {
                // IF trip is from fixed location to same fixed location. Impute a randomly sampled Round-trip.
                val carDistance = rtDistances[originActivity.type]!!
                val routes = Mode.entries.filter { it != Mode.UNDEFINED }.associateWith {
                    Route.getRoundTripRoute(it, carDistance)
                }
                carDistance to routes
            }  else {
                val routes = Mode.entries.filter { it != Mode.UNDEFINED }.associateWith {
                    Route.getWithFallback(
                        it, originActivity.location, destinationActivity.location,
                        hopper, withPath, departureInstant, ptRouter
                    )
                }
                val carDistance = routes[Mode.CAR_DRIVER]!!.distance
                carDistance to routes
            }

            val tripFeatures = TripMCFeatures(
                carDistance,
                originActivity,
                destinationActivity,
                routes
            )
            currentTour.add(tripFeatures)

            // Tour ends
            if ((destinationActivity.type == ActivityType.HOME) || finished){
                tours.add(currentTour)
                currentTour = mutableListOf()
            }

            // Add estimated travel time
            trip.time = routes[Mode.CAR_DRIVER]!!.time
        }
        diary.visitTrips(visitor) // Run through day
        return tours
    }

    /**
     * Do Mode choice for a single Agent.
     * The result is directly stored in the agents diaries.
     *
     * @param agent Agent
     * @param rng Random number generator
     */
    private fun doModeChoiceFor(agent: MobiAgent, rng: Random) {
        for (diary in agent.mobilityDemand) {
            val tours = getTours(diary, rng)

            // Tour mode choice
            for (tour in tours) {
                // Only do HOME-HOME tours as one block
                if (tour.first().fromActivity.type != ActivityType.HOME) { continue }
                if (tour.last().toActivity.type != ActivityType.HOME) { continue }

                // Aggregate distance and times
                val carDistance = tour.sumOf { it.carDistance }
                val times = tourModeOptions.map { m -> tour.sumOf { it.routes[m.mode]!!.time } }.toTypedArray()

                // Main purpose of tour is defined by the activity with the longest stay time
                val mainPurpose = tour
                    .dropLast(1)
                    .maxByOrNull { it.toActivity.stayTime!! }?.toActivity?.type ?: ActivityType.HOME
                val mode = sampleUtilities(tourModeOptions, times, carDistance, agent, mainPurpose, rng)

                // If the tour is a CAR or BICYCLE all trips on the tour must be conducted with the respective vehicle
                if ((mode == Mode.CAR_DRIVER) || (mode == Mode.BICYCLE)) {
                    for (trip in tour) {
                        trip.mode = mode
                    }
                }
            }

            // Trip mode choice
            for (trip in tours.flatten().filter { it.mode == null }) {
                val times = tripModeOptions.map { m ->
                    trip.routes[m.mode]!!.time
                }.toTypedArray()
                val mode = sampleUtilities(tripModeOptions, times, trip.carDistance, agent, trip.toActivity.type, rng)
                trip.mode = mode
            }

            // Format for output
            val outTrips = mutableListOf<Trip>()
            for (trip in tours.flatten()) {
                // Check if gtfs routing used any public transit or only walked
                val mode = if (trip.routes[trip.mode!!]!!.onlyWalk) {
                   Mode.FOOT
                } else {
                   trip.mode!!
                }

                // GraphHopper PT-Distance currently only includes the walking portion
                val distance = if (mode == Mode.PUBLIC_TRANSIT) {
                    trip.carDistance
                } else {
                    trip.routes[mode]!!.distance
                }

                outTrips.add(
                    Trip(
                        distance,
                        trip.routes[mode]!!.time,
                        mode = mode,
                        lats = trip.routes[mode]!!.lats,
                        lons = trip.routes[mode]!!.lons
                    )
                )
            }
            diary.trips = outTrips
        }
    }

    /**
     * Sample logit model defined by the given utilities.
     *
     * @param options Possible modes for the decision (Different for tours and trips)
     * @param times Travel times for each option
     * @param carDistance Distance by car. Used as the reference distance.
     * @param agent Agent
     * @param activity Main activity of the tour or purpose of the trip.
     * @param rng Random number generator
     * @return Chosen mode
     */
    private fun sampleUtilities(
        options: Array<ModeUtility>, times: Array<Double>,
        carDistance: Double, agent: MobiAgent, activity: ActivityType, rng: Random
    ) : Mode {
        // If public transit and foot routes are the same, add 20 minutes to public transit to differentiate the options
        val footIdx = options.withIndex().find { (_, o) -> o.mode == Mode.FOOT }?.index
        val ptIdx = options.withIndex().find { (_, o) -> o.mode == Mode.PUBLIC_TRANSIT }?.index
        if ((footIdx != null) && (ptIdx != null)) {
            if (abs(times[footIdx] - times[ptIdx]) <= 3) {
                times[ptIdx] = times[ptIdx] + 20.0
            }
        }

        // Sampling
        val weights = options.withIndex()
            .map { (i, util) -> exp(util.calc(times[i], carDistance, activity, null, agent)) }
            .toDoubleArray()
        val distr = createCumDist(weights)
        val mode = options[sampleCumDist(distr, rng)].mode
        return mode
    }

    /**
     * Utility data class that stores all features of a trip required by the logit model.
     * Also stores the result of the tour level decision for trip level decisions.
     *
     * @param carDistance Distance by car
     * @param fromActivity Activity before the trip
     * @param toActivity Activity after the trip
     * @param routes Best routes with each mode
     */
    private class TripMCFeatures (
        val carDistance: Double,
        val fromActivity: Activity,
        val toActivity: Activity,
        val routes: Map<Mode, Route>
    ) {
        var mode: Mode? = null
    }
}