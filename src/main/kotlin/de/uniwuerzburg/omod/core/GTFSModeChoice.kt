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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.time.TimeSource

// TODO car availability
// TODO Test with no car availability or person features
// TODO to much car trips right now
// TODO test somewhere with better OPNV
// TODO why is bicycle unlikely? Maybe because of Tour main activity -> H is high coefficient
// TODO Caching options

class GTFSModeChoice(
    private val hopper: GraphHopper,
    private val ptRouter: PtRouter,
    private val routingCache: RoutingCache,
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

    private fun getRoutes(
        mode: Mode, origin: LocationOption, destination: LocationOption, departureTime: Instant
    ): Route {
        if ((origin !is RealLocation) || (destination !is RealLocation)) {
            return routeFallback(mode, origin, destination)
        } else {
            val response = when (mode) {
                Mode.PUBLIC_TRANSIT -> routeGTFS(origin, destination, departureTime, ptRouter, hopper)
                Mode.FOOT -> routeWith("foot", origin, destination, hopper)
                Mode.BICYCLE -> routeWith("bike", origin, destination, hopper)
                else -> routeWith("car", origin, destination, hopper)
            }
            return if (response.hasErrors()) {
                routeFallback(mode, origin, destination)
            } else {
                Route.fromGHResponse(response, withPath)
            }
        }
    }

    /**
     * Run through day and get all HOME-HOME tours. If the day does not start with a HOME activity,
     * all the trips before the first HOME activity are counted as a tour. The same is true for all the trips after the
     * last HOME activity if the day does not end with a HOME activity.
     */
    private fun getTours(diary: Diary) : List<List<TripMCFeatures>> {
        val tours = mutableListOf<List<TripMCFeatures>>()
        var currentTour = mutableListOf<TripMCFeatures>()

        val visitor = {
            trip: Trip, originActivity: Activity, destinationActivity: Activity,
            departureTime: LocalTime, wd: Weekday, finished: Boolean ->
            val departureInstant = ptSimDays[wd]!!.atTime(departureTime).atZone(timeZone.toZoneId()).toInstant()

            // Get car distance and travel times of trip
            val carDistance = routingCache.getDistances(
                originActivity.location, listOf(destinationActivity.location)
            ).first().toDouble() / 1000
            val routes = Mode.entries.associateWith {
                getRoutes(it, originActivity.location, destinationActivity.location, departureInstant)
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
            val tours = getTours(diary)

            // Tour mode choice
            for (tour in tours) {
                // Only do HOME-HOME tours as one block
                if (tour.first().fromActivity.type != ActivityType.HOME) { continue }
                if (tour.last().toActivity.type != ActivityType.HOME) { continue }

                // Aggregate distance and times
                val carDistance = tour.sumOf { it.carDistance }
                val times = tourModeOptions.map { m ->
                    tour.sumOf { it.routes[m.mode]!!.time }
                }.toTypedArray()

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
                outTrips.add(
                    Trip(
                        trip.carDistance,
                        trip.routes[trip.mode!!]!!.time,
                        mode = trip.mode!!,
                        lats =  trip.routes[trip.mode!!]!!.lats,
                        lons =  trip.routes[trip.mode!!]!!.lons
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
            .map { (i, util) -> exp(util.calc(times[i], carDistance, activity, true, agent)) }
            .toDoubleArray()
        val distr = createCumDist(weights)
        return options[sampleCumDist(distr, rng)].mode
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