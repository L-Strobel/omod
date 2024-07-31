package de.uniwuerzburg.omod.core.assignment

import com.graphhopper.GHResponse
import org.locationtech.jts.geom.Coordinate

/**
 * Route. Result of assignment.
 *
 * @param distance Distance of the route. Unit: meter
 * @param time Travel time. Unit: seconds
 * @param path Coordinates of the path. In lat-lon format.
 * @param isReal Is the route real? Meaning does it use the real road network. As opposed
 * to estimated distances and travel times, for example, based on beeline distance.
 */
class Route (
    val distance: Double,           // Unit: meter
    val time: Double,               // Unit: seconds
    val path: List<Coordinate>?,
    val isReal: Boolean             // Between real locations?
) {
    companion object {
        /**
         * Create a route from GraphHopper response.
         * @param response GraphHopper response
         * @param withPath Store path coordinates in the route?
         * @return Route
         */
        fun fromGH(response: GHResponse, withPath: Boolean): Route? {
            if (response.hasErrors()) {
                return null
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
