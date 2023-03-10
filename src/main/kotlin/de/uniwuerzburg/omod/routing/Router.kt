package de.uniwuerzburg.omod.routing

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.isochrone.algorithm.ShortestPathTree
import com.graphhopper.routing.ev.Subnetwork
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.index.Snap
import de.uniwuerzburg.omod.core.LocationOption
import de.uniwuerzburg.omod.core.RealLocation

/**
 * Calculate the euclidean distance between two locations
 */
fun calcDistanceBeeline(origin: LocationOption, destination: LocationOption) : Double {
    return origin.coord.distance(destination.coord)
}

/**
 * Determine the shortest path between two locations using a car
 */
fun routeWithCar (origin: RealLocation, destination: RealLocation, hopper: GraphHopper) : GHResponse {
    val req = GHRequest(
        origin.latlonCoord.x,
        origin.latlonCoord.y,
        destination.latlonCoord.x,
        destination.latlonCoord.y
    )
    req.profile = "custom_car"
    return hopper.route(req)
}

data class PreparedQGraph (
    val queryGraph: QueryGraph,
    val weighting: Weighting,
    val locNodes: Map<LocationOption, Int>,
    val snapNodes: Set<Int>
)

fun prepareQGraph(hopper: GraphHopper, locsToSnap: List<RealLocation>) : PreparedQGraph {
    val encodingManager = hopper.encodingManager
    val accessEnc = encodingManager.getBooleanEncodedValue(VehicleAccess.key("car"))
    val speedEnc = encodingManager.getDecimalEncodedValue(VehicleSpeed.key("car"))
    val weighting = FastestWeighting(accessEnc, speedEnc)

    val snaps = mutableListOf<Snap>()
    val locNodes = mutableMapOf<LocationOption, Int>()
    val snapNodes = mutableSetOf<Int>()
    for (loc in locsToSnap) {
        val snap = hopper.locationIndex.findClosest(
            loc.latlonCoord.x,
            loc.latlonCoord.y,
            DefaultSnapFilter(weighting, encodingManager.getBooleanEncodedValue(Subnetwork.key("custom_car")))
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

data class SPTResult (
    val distance: Double,   // Unit: Meter
    val time: Double        // Unit: Milliseconds
)

fun querySPT(preparedQGraph: PreparedQGraph, origin: RealLocation, destinations: List<LocationOption>) : List<SPTResult?> {
    val originNode = preparedQGraph.locNodes[origin]

    // Origin node must be in graph
    if (originNode == null) {
        logger.warn("Origin: ${origin.latlonCoord} was not in the prepared graph.")
        return List(destinations.size) { null }
    }

    // Get estimate of max distance for speed up
    val maxDistanceBeeline = destinations.maxOf { calcDistanceBeeline(origin, it) }

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