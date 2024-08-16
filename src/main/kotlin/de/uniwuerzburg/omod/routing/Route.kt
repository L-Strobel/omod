package de.uniwuerzburg.omod.routing

import com.graphhopper.GHResponse

class Route (
    val distance: Double,           // Unit: meter
    val time: Double,               // Unit: seconds
    val lats: List<Double>?,
    val lons: List<Double>?
) {
    companion object {
        fun fromGHResponse(response: GHResponse, withPath: Boolean) : Route {
            val (lats, lons) = if (withPath) {
                Pair(
                    response.best.points.map { it.lat },
                    response.best.points.map { it.lon }
                )
            } else {
                Pair(null, null)
            }
            return Route (
                response.best.distance / 1000,
                (response.best.time / 1000 / 60).toDouble(),
                lats,
                lons
            )
        }
    }
}