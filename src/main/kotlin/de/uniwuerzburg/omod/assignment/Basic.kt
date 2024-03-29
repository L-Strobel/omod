package de.uniwuerzburg.omod.assignment

import com.graphhopper.GraphHopper
import de.uniwuerzburg.omod.core.*
import de.uniwuerzburg.omod.io.OutputTDiary
import de.uniwuerzburg.omod.io.OutputTEntry
import de.uniwuerzburg.omod.io.OutputTrip
import de.uniwuerzburg.omod.routing.calcDistanceBeeline
import de.uniwuerzburg.omod.routing.prepareQGraph
import de.uniwuerzburg.omod.routing.querySPT
import de.uniwuerzburg.omod.routing.routeWithCar

/**
 * Determine the direct (beeline) route from origin to destination.
 *
 * @param origin Origin
 * @param destination Destination
 * @param speedBeeline Speed (used for travel time calculation)
 * @return Dummy route that contains only distance and travel time but no path.
 */
fun beelineRoute(origin: LocationOption, destination: LocationOption, speedBeeline: Double = 130.0 / 3.6) : Route {
    val distance = calcDistanceBeeline(origin, destination)
    val time = distance / speedBeeline
    return Route(distance, time, null, false)
}

/**
 * Return route from origin to destination. If both origin and destination are real location (within the .osm.pbf file)
 * the fastest route by car is returned. Otherwise, the beeline route see beelineRoute().
 *
 * @param origin Origin
 * @param destination Destination
 * @param hopper GraphHopper
 * @param withPath Include the path in the route.
 * @return Route
 */
fun route(origin: LocationOption, destination: LocationOption, hopper: GraphHopper, withPath: Boolean = false) : Route {
    return if ((origin !is RealLocation) or (destination !is RealLocation)) {
        beelineRoute(origin, destination)
    } else {
        val route = Route.fromGH(routeWithCar(origin as RealLocation, destination as RealLocation, hopper), withPath)
        route ?: beelineRoute(origin, destination) // Fallback to beeline
    }
}

/**
 * Cluster trip start and stop locations to speed up routing.
 *
 * @param agents Agents for which assignment will be done
 * @param precision Precision of the routing grid (average distance from a location to its associated centroid)
 * @param transformer Used for CRS conversions
 * @return Map: Building -> Routing cell
 */
fun makeRoutingGrid(agents: List<MobiAgent>, precision: Double, transformer: CRSTransformer)
    : Map<LocationOption, LocationOption> {
    // Get all trip start/stop locations
    val buildings = mutableListOf<Building>()
    val otherLocs = mutableListOf<LocationOption>()
    for (agent in agents) {
        for (diary in agent.mobilityDemand) {
            for (activity in diary.activities) {
                if (activity.location is Building) {
                    buildings.add( activity.location )
                } else {
                    otherLocs.add( activity.location )
                }
            }
        }
    }

    return if (precision > 0) {
        val grid = cluster(precision, buildings, geometryFactory, transformer)

        // Mapping: Building -> Cell
        val routingGrid = mutableMapOf<LocationOption, LocationOption>()
        for (cell in grid) {
            for (building in cell.buildings) {
                routingGrid[building] = cell as LocationOption
            }
        }
        for (loc in otherLocs) {
            routingGrid[loc] = loc
        }

        routingGrid
    } else {
        buildings.associateWith { it } + otherLocs.associateWith { it } // Map all locations to themselves
    }
}

/**
 * [Experimental] Assign trips in an all-or-nothing manner. Meaning that every agent chooses the route that is fastest
 * by car and without considering congestion effects.
 * Warning: all-or-nothing assignment is generally not realistic in urban areas.
 *
 * @param agents Agents for which trips should be assigned
 * @param hopper GraphHopper object
 * @param transformer Used for CRS transformation
 * @param withPath Store the path taken by each agent in the output (lat-lon coordinates)
 * @param precision Precision of the routing grid
 * @return Routes chosen by agents
 */
fun allOrNothing(agents: List<MobiAgent>, hopper: GraphHopper, transformer: CRSTransformer,
                 withPath: Boolean = true, precision: Double = 50.0) : List<OutputTEntry> {
    // Performance parameter: If there are more than threshTree trips starting at one location route with SPT.
    val threshTree = 10_000
    val routingGrid = makeRoutingGrid(agents, precision, transformer)

    // Get all od-Pairs that have to be routed
    val odMatrix = mutableMapOf<LocationOption, MutableList<LocationOption>>()
    for (agent in agents) {
        for (diary in agent.mobilityDemand) {
            if (diary.activities.size <= 1) {
                continue
            }

            var origin = routingGrid[diary.activities.first().location]!!
            for (activity in diary.activities.drop(1)) {
                val destination = routingGrid[activity.location]!!

                if (odMatrix.containsKey(origin)) {
                    odMatrix[origin]!!.add(destination)
                } else {
                    odMatrix[origin] = mutableListOf( destination )
                }

                origin = destination
            }
        }
    }

    // Assign
    val routes = mutableMapOf<LocationOption, Map<LocationOption, Route>>()
    var jobsDone = 0
    val totalJobs = odMatrix.size.toDouble()
    for ((origin, destinations) in odMatrix.entries) {
        print( "Assigning routes: ${ProgressBar.show( jobsDone / totalJobs )}\r" )

        if ((origin !is RealLocation) or (destinations.size <= threshTree) or (withPath)) {
            // Route point to point
            routes[origin] = destinations.associateWith { route(origin, it, hopper, withPath) }
        } else {
            // Route one to many with SPT
            val qGraph = prepareQGraph(hopper, (destinations + origin).filterIsInstance<RealLocation>())
            val sptResults = querySPT(qGraph, origin as RealLocation, destinations)

            routes[origin] = sptResults.mapIndexed { i, it ->
                val destination = destinations[i]
                val route = if (it == null) {
                    route(origin, destinations[i], hopper, withPath)
                } else {
                    Route(it.distance, it.time / 1000, null, true)
                }

                destination to route
            }.toMap()
        }
        jobsDone += 1
    }
    println("Assigning routes: " + ProgressBar.done())

    // Create output
    val output = mutableListOf<OutputTEntry>()
    for (agent in agents) {
        val outDiary = mutableListOf<OutputTDiary>()
        for (diary in agent.mobilityDemand) {
            val trips = mutableListOf<OutputTrip>()

            if (diary.activities.size > 1) {
                var origin = routingGrid[diary.activities.first().location]!!
                for (activity in diary.activities.drop(1)) {
                    val destination = routingGrid[activity.location]!!

                    // Find the route
                    val route = routes[origin]!![destination]!!
                    trips.add(
                        OutputTrip(
                            route.distance,
                            route.time,
                            route.path?.map { it.x },
                            route.path?.map { it.y },
                            route.isReal
                        )
                    )

                    origin = destination
                }
            }
            outDiary.add( OutputTDiary(diary.day, trips) )
        }
        output.add(OutputTEntry(agent.id, outDiary))
    }
    return output
}

