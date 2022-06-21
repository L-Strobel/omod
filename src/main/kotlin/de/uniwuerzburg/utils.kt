package de.uniwuerzburg

import org.locationtech.jts.geom.Coordinate
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
