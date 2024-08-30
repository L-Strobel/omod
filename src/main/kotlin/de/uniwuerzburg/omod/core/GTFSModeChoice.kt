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
import kotlin.math.exp
import kotlin.time.TimeSource

// TODO car availability
// TODO take lower of foot and pt for pt

class GTFSModeChoice(
    private val hopper: GraphHopper,
    private val ptRouter: PtRouter,
    private val ptSimDays: Map<Weekday, LocalDate>,
    private val timeZone: TimeZone,
    private val withPath: Boolean
) : ModeChoice {
    private val tourModeOptions: Array<ModeUtility> = readJsonFromResource("tourModeUtilities.json")
    private val tripModeOptions: Array<ModeUtility> = readJsonFromResource("tripModeUtilities.json")

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
                val routes = Mode.entries.associateWith {
                    Route.getRoundTripRoute(it, carDistance)
                }
                carDistance to routes
            }  else {
                val routes = Mode.entries.associateWith {
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

    private fun doModeChoiceFor(agent: MobiAgent, rng:Random) {
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
                        trip.carDistance,
                        trip.routes[trip.mode!!]!!.time,
                        mode = trip.mode!!,
                        lats = trip.routes[trip.mode!!]!!.lats,
                        lons = trip.routes[trip.mode!!]!!.lons
                    )
                )
            }
            diary.trips = outTrips
        }
    }

    /**
     * Sample logit model defined by the given utilities.
     */
    private fun sampleUtilities(
        options: Array<ModeUtility>, times: Array<Double>,
        carDistance: Double, agent: MobiAgent, activity: ActivityType, rng: Random
    ) : Mode {
        val weights = options.withIndex()
            .map { (i, util) -> exp(util.calc(times[i], carDistance, activity, null, agent)) }
            .toDoubleArray()
        val distr = createCumDist(weights)
        val mode = options[sampleCumDist(distr, rng)].mode
        return mode
    }

    /**
     * Utility data class that stores all features of a trip required by the logit model.
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