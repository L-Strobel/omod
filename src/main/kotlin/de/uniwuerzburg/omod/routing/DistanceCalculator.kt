package de.uniwuerzburg.omod.routing

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.isochrone.algorithm.ShortestPathTree
import com.graphhopper.routing.ev.Subnetwork
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.index.Snap
import de.uniwuerzburg.omod.core.LocationOption
import de.uniwuerzburg.omod.core.RealLocation
import org.slf4j.LoggerFactory


private val logger = LoggerFactory.getLogger("de.uniwuerzburg.omod.routing.DistanceCalculator")

/**
 * Calculate the euclidean distance between two locations
 */
fun calcDistanceBeeline(origin: LocationOption, destination: LocationOption) : Double {
    return origin.coord.distance(destination.coord)
}

/**
 * Calculate the distance between two locations using a car
 */
fun calcDistanceGH (origin: RealLocation, destination: RealLocation, hopper: GraphHopper) : GHResponse {
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
    val encoder = encodingManager.getEncoder("car")
    val weighting = FastestWeighting(encoder)

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
    val queryGraph = QueryGraph.create(hopper.graphHopperStorage.baseGraph, snaps)
    return PreparedQGraph(queryGraph, weighting, locNodes, snapNodes)
}

fun querySPT(preparedQGraph: PreparedQGraph, origin: RealLocation, destinations: List<LocationOption>) : List<Double?> {
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
    val treeDistances = mutableMapOf<Int, Double>()
    tree.search(originNode) { label ->
        if (label.node in preparedQGraph.snapNodes) {
            treeDistances[label.node] = label.distance
        }
    }

    // Format information
    val distances = mutableListOf<Double?>()
    for (destination in destinations) {
        val destinationNode = preparedQGraph.locNodes[destination]

        val distance = if (origin == destination) {
            origin.avgDistanceToSelf
        } else {
            treeDistances[destinationNode]
        }

        distances.add(distance)
    }
    return distances
}