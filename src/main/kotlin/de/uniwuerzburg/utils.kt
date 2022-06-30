package de.uniwuerzburg

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.*

infix fun ClosedRange<Double>.step(step: Double): Iterable<Double> {
    require(start.isFinite())
    require(endInclusive.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next > endInclusive) null else next
    }
    return sequence.asIterable()
}
fun semiOpenDoubleRange(start: Double, end: Double, step: Double): Iterable<Double> {
    require(start.isFinite())
    require(end.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next >= end) null else next
    }
    return sequence.asIterable()
}

fun Boolean.toInt() = if (this) 1 else 0

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

// Find envelops that are covered by a geometry quickly
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

