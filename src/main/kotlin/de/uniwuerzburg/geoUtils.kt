package de.uniwuerzburg

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
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

        // If routing didn't work fall back to beeline
        if (rsp.hasErrors()) {
            origin.coord.distance(destination.coord)
        } else {
            rsp.best.distance
        }
    }
}