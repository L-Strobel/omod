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
import org.locationtech.jts.geom.Point

fun beelineRoute(origin: LocationOption, destination: LocationOption, speedBeeline: Double = 130.0 / 3.6) : Route {
    val distance = calcDistanceBeeline(origin, destination)
    val time = distance / speedBeeline
    return Route(distance, time, null, false)
}

@Suppress("unused")
fun route(origin: LocationOption, destination: LocationOption, hopper: GraphHopper, withPath: Boolean = false) : Route {
    return if ((origin !is RealLocation) or (destination !is RealLocation)) {
        beelineRoute(origin, destination)
    } else {
        val route = Route.fromGH(routeWithCar(origin as RealLocation, destination as RealLocation, hopper), withPath)
        route ?: beelineRoute(origin, destination) // Fallback to beeline
    }
}

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

fun getCRSTransformer(agents: List<MobiAgent>): CRSTransformer {
    val points = mutableSetOf<Point>()
    for (agent in agents) {
        for (diary in agent.mobilityDemand) {
            for (activity in diary.activities.drop(0)) {
                val coord = (activity.location as? Building)?.latlonCoord
                if (coord != null) {
                    points.add( geometryFactory.createPoint(coord) )
                }
            }
        }
    }

    val center = geometryFactory.createGeometryCollection(
        points.toTypedArray()
    ).union().centroid

    return CRSTransformer( center.coordinate.x, center.coordinate.y)
}

fun allOrNothing(agents: List<MobiAgent>, hopper: GraphHopper, withPath: Boolean = true,
                 precision: Double = 50.0) : List<OutputTEntry> {
    // Performance parameter: If there are more than threshTree trips starting at one location route with SPT.
    val threshTree = 10_000
    val routingGrid = makeRoutingGrid(agents, precision, getCRSTransformer(agents))

    // Get all od-Pairs that have to be routed
    val odMatrix = mutableMapOf<LocationOption, MutableList<LocationOption>>()
    for (agent in agents) {
        for (diary in agent.mobilityDemand) {
            if (diary.activities.size <= 1) {
                continue
            }

            var origin = routingGrid[diary.activities.first().location]!!
            for (activity in diary.activities.drop(0)) {
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
            val sptResults = querySPT(qGraph, origin as RealLocation, destinations.filterIsInstance<RealLocation>())

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

