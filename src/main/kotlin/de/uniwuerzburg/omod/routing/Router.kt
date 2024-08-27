package de.uniwuerzburg.omod.routing

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.gtfs.PtRouter
import com.graphhopper.gtfs.Request
import com.graphhopper.isochrone.algorithm.ShortestPathTree
import com.graphhopper.routing.ev.Subnetwork
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.PMap
import de.uniwuerzburg.omod.core.models.LocationOption
import de.uniwuerzburg.omod.core.models.Mode
import de.uniwuerzburg.omod.core.models.RealLocation
import java.time.Instant
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

/**
 * Calculate the Euclidean distance between two locations
 *
 * @param origin Location A
 * @param destination Location B
 * @return distance
 */
fun calcDistanceBeeline(origin: LocationOption, destination: LocationOption) : Double {
    return origin.coord.distance(destination.coord)
}

/**
 * Determine the best path between two locations using the given profile.
 *
 * @param origin Location A
 * @param destination Location B
 * @param hopper GraphHopper object
 * @return GHResponse (Wrapper around the best route)
 */
fun routeWith (profile: String, origin: RealLocation, destination: RealLocation, hopper: GraphHopper) : GHResponse {
    val req = GHRequest(
        origin.latlonCoord.x,
        origin.latlonCoord.y,
        destination.latlonCoord.x,
        destination.latlonCoord.y
    )
    req.profile = profile
    return hopper.route(req)
}

/**
 * Determine the best path between two locations using public transit.
 *
 * @param origin Location A
 * @param destination Location B
 * @param departureTime Time of earliest possible departure
 * @param ptRouter PtRouter object
 * @return GHResponse (Wrapper around the best route)
 */
fun routeGTFS (
    origin: RealLocation, destination: RealLocation, departureTime: Instant, ptRouter: PtRouter, hopper: GraphHopper
) : GHResponse {
    val fromLat = origin.latlonCoord.x
    val fromLon = origin.latlonCoord.y
    val toLat = destination.latlonCoord.x
    val toLon = destination.latlonCoord.y

    if ((abs(fromLat - toLat) <= 0.0001) && (abs(fromLon - toLon) <= 0.0001)) {
        return routeWith("foot", origin, destination, hopper)
    } else {
        val req = Request(
            origin.latlonCoord.x,
            origin.latlonCoord.y,
            destination.latlonCoord.x,
            destination.latlonCoord.y
        )
        req.earliestDepartureTime = departureTime
        val resp = try {
           ptRouter.route(req)
        } catch (e: Exception){
            routeWith("foot", origin, destination, hopper)
        }
        return resp
    }
}

/**
 * Prepared query graph used in ShortestPathTree calculation.
 *
 * @param queryGraph query graph
 * @param weighting weighting to use
 * @param locNodes Map from relevant location to node in graph
 * @param snapNodes Nodes in the graph that correspond to a relevant location
 */
data class PreparedQGraph (
    val queryGraph: QueryGraph,
    val weighting: Weighting,
    val locNodes: Map<LocationOption, Int>,
    val snapNodes: Set<Int>
)

/**
 * Prepare for ShortestPathTree calculation.
 *
 * @param hopper GraphHopper object
 * @param locsToSnap Locations that will be relevant in SPT run (origin + possible destinations)
 * @return prepared graph
 */
fun prepareQGraph(hopper: GraphHopper, locsToSnap: List<RealLocation>) : PreparedQGraph {
    val encodingManager = hopper.encodingManager
    val weighting = hopper.createWeighting(hopper.getProfile("car"), PMap())

    val snaps = mutableListOf<Snap>()
    val locNodes = mutableMapOf<LocationOption, Int>()
    val snapNodes = mutableSetOf<Int>()
    for (loc in locsToSnap) {
        val snap = hopper.locationIndex.findClosest(
            loc.latlonCoord.x,
            loc.latlonCoord.y,
            DefaultSnapFilter(weighting, encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
        )
        if (snap.isValid) {
            snaps.add(snap)

            val node = snap.closestNode
            snapNodes.add(node)
            locNodes[loc] = node
        } else {
            logger.warn("Couldn't snap ${loc.latlonCoord}.")
        }
    }
    val queryGraph = QueryGraph.create(hopper.baseGraph, snaps)
    return PreparedQGraph(queryGraph, weighting, locNodes, snapNodes)
}

/**
 * Result of ShortestPathTree search.
 * @param distance Distance to the destination. Unit: Meter
 * @param time Travel time. Unit: Milliseconds
 */
data class SPTResult (
    val distance: Double,   // Unit: Meter
    val time: Double        // Unit: Milliseconds
)

/**
 * Get the shortest distance and travel time from an origin to many destinations. Utilizes an ShortestPathTree.
 *
 * @param preparedQGraph Prepared query graph
 * @param origin Start location
 * @param destinations List of possible destinations
 * @return Shortest distance and travel time to each destination
 */
fun querySPT(preparedQGraph: PreparedQGraph, origin: RealLocation, destinations: List<LocationOption>) : List<SPTResult?> {
    val originNode = preparedQGraph.locNodes[origin]

    // Origin node must be in graph
    if (originNode == null) {
        logger.warn("Origin: ${origin.latlonCoord} was not in the prepared graph.")
        return List(destinations.size) { null }
    }

    // Get estimate of max distance for speed up
    val maxDistanceBeeline = destinations.filterIsInstance<RealLocation>().maxOf { calcDistanceBeeline(origin, it) }

    // Build shortest path tree
    val tree = ShortestPathTree(
        preparedQGraph.queryGraph,
        preparedQGraph.weighting,
        false,
        TraversalMode.NODE_BASED
    )

    // Reduce tree. I hope routing distances are almost never twice as long as the beeline.
    tree.setDistanceLimit(maxDistanceBeeline * 2)

    // Query
    val treeResults = mutableMapOf<Int, SPTResult>()
    tree.search(originNode) { label ->
        if (label.node in preparedQGraph.snapNodes) {
            val result = SPTResult(label.distance, label.time.toDouble())
            treeResults[label.node] = result
        }
    }

    // Format information
    val results = mutableListOf<SPTResult?>()
    for (destination in destinations) {
        val destinationNode = preparedQGraph.locNodes[destination]

        val result = if (origin == destination) {
            SPTResult(origin.avgDistanceToSelf, 0.0)
        } else {
            treeResults[destinationNode]
        }

        results.add(result)
    }
    return results
}