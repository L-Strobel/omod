package de.uniwuerzburg

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.isochrone.algorithm.ShortestPathTree
import com.graphhopper.routing.ev.Subnetwork
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.index.Snap
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.*

// Earth radius according to WGS 84
const val earthMajorAxis = 6378137.0

fun latlonToMercator(lat: Double, lon: Double) : Coordinate {
    val radLat = lat * PI / 180.0
    val radLon = lon * PI / 180.0

    val x = earthMajorAxis * (radLon)
    val y = earthMajorAxis * ln(tan(radLat / 2 + PI / 4))
    return Coordinate(x, y)
}

fun mercatorToLatLon(x: Double, y: Double) : Coordinate {
    val radLon = x / earthMajorAxis
    val radLat = (atan(exp(y / earthMajorAxis)) - PI / 4) * 2

    val lon = radLon * 180.0 / PI
    val lat = radLat * 180.0 / PI
    return Coordinate(lat, lon)
}

/**
 * Find envelops that are covered by a geometry quickly
 */
fun fastCovers(geometry: Geometry, resolutions: List<Double>, geometryFactory: GeometryFactory,
               ifNot: (Envelope) -> Unit, ifDoes: (Envelope) -> Unit, ifUnsure: (Envelope) -> Unit) {
    val envelope = geometry.envelopeInternal!!
    val resolution = resolutions.first()
    for (x in semiOpenDoubleRange(envelope.minX, envelope.maxX, resolution)) {
        for (y in semiOpenDoubleRange(envelope.minY, envelope.maxY, resolution)) {
            val smallEnvelope = Envelope(x, min(x + resolution, envelope.maxX), y, min(y + resolution, envelope.maxY))
            val smallGeom = geometryFactory.toGeometry(smallEnvelope)

            if (geometry.disjoint(smallGeom)) {
                ifNot(smallEnvelope)
            } else if (geometry.contains(smallGeom)) {
                ifDoes(smallEnvelope)
            } else {
                val newResolutions = resolutions.drop(1)
                if (newResolutions.isEmpty()) {
                    ifUnsure(smallEnvelope)
                } else {
                    fastCovers(geometry.intersection(smallGeom), newResolutions, geometryFactory,
                        ifNot = ifNot, ifDoes = ifDoes,  ifUnsure = ifUnsure)
                }
            }
        }
    }
}

/**
 * Calculate the euclidean distance between two locations
 */
fun calcDistanceBeeline(origin: LocationOption, destination: LocationOption) : Double {
    return if (origin == destination) {
        origin.avgDistanceToSelf // 0.0 for Buildings
    } else {
        origin.coord.distance(destination.coord)
    }
}

/**
 * Calculate the distance between two locations using a car
 */
fun calcDistanceGH (origin: RealLocation, destination: RealLocation, hopper: GraphHopper) : Double {
    return if (origin == destination) {
        origin.avgDistanceToSelf // 0.0 for Buildings
    } else {
        val req = GHRequest(
            origin.latlonCoord.x,
            origin.latlonCoord.y,
            destination.latlonCoord.x,
            destination.latlonCoord.y
        )
        req.profile = "car"
        val rsp = hopper.route(req)

        rsp.best.distance
    }
}

data class PreparedQGraph (
    val queryGraph: QueryGraph,
    val weighting: Weighting,
    val locNodes: Map<LocationOption, Int>,
    val snapNodes: Set<Int>
)

fun prepareQGraph(hopper: GraphHopper, locsToSnap: List<RealLocation>) : PreparedQGraph{
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
            DefaultSnapFilter(weighting, encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
        )
        if (snap.isValid) {
            snaps.add(snap)

            val node = snap.closestNode
            snapNodes.add(node)
            locNodes[loc] = node
        }
    }
    val queryGraph = QueryGraph.create(hopper.graphHopperStorage.baseGraph, snaps)
    return PreparedQGraph(queryGraph, weighting, locNodes, snapNodes)
}

fun querySPT(preparedQGraph: PreparedQGraph, origin: RealLocation, destinations: List<LocationOption>) : List<Double?> {
    val originNode = preparedQGraph.locNodes[origin]!! // Origin must be snapped to query graph

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
