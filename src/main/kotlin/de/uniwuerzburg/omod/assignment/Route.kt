package de.uniwuerzburg.omod.assignment

import com.graphhopper.GHResponse
import org.locationtech.jts.geom.Coordinate

@Suppress("MemberVisibilityCanBePrivate", "unused")
class Route (
    val distance: Double,           // Unit: meter
    val time: Double,               // Unit: seconds
    val path: List<Coordinate>?,
    val isReal: Boolean             // Between real locations?
) {
    companion object {
        fun fromGH(response: GHResponse, withPath: Boolean): Route {
            if (response.hasErrors()) {
                throw response.errors[0]
            }
            val path = if (withPath) {
                response.best.points.map {
                    Coordinate(it.getLat(), it.getLon())
                }
            } else {
                null
            }

            return Route(
                response.best.distance,
                (response.best.time / 1000).toDouble(),
                path,
                true
            )
        }
    }
}