package de.uniwuerzburg.omod.routing

import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.gtfs.PtRouter
import de.uniwuerzburg.omod.core.models.LocationOption
import de.uniwuerzburg.omod.core.models.Mode
import de.uniwuerzburg.omod.core.models.RealLocation
import java.time.Instant
import java.util.*
import kotlin.math.ln

class Route (
    val distance: Double,           // Unit: kilometer
    val time: Double,               // Unit: minutes
    val lats: List<Double>?,
    val lons: List<Double>?
) {
    companion object {
        fun getWithFallback(
            mode: Mode, origin: LocationOption, destination: LocationOption, hopper: GraphHopper, withPath: Boolean,
            departureTime: Instant?, ptRouter: PtRouter?
        ): Route {
            if (((departureTime == null) || (ptRouter == null)) && (mode == Mode.PUBLIC_TRANSIT)) {
                throw IllegalArgumentException("A ptRouter and departureTime is required for public transit routing!")
            }

            val route = if ((origin !is RealLocation) || (destination !is RealLocation)) {
                routeFallback(mode, origin, destination)
            } else {
                val response = when (mode) {
                    Mode.PUBLIC_TRANSIT -> routeGTFS(origin, destination, departureTime!!, ptRouter!!, hopper)
                    Mode.FOOT           -> routeWith("foot", origin, destination, hopper)
                    Mode.BICYCLE        -> routeWith("bike", origin, destination, hopper)
                    else                -> routeWith("car", origin, destination, hopper)
                }
                if (response.hasErrors()) {
                    routeFallback(mode, origin, destination)
                } else {
                    fromGHResponse(response, withPath)
                }
            }

            if (mode == Mode.CAR_DRIVER || mode == Mode.CAR_PASSENGER) {
                return Route(route.distance, route.time + 5, route.lats, route.lons) // 5min for parking
            }
            return route
        }

        private fun fromGHResponse(response: GHResponse, withPath: Boolean) : Route {
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

        fun routeFallback(mode: Mode, origin: LocationOption, destination: LocationOption): Route {
            val beelineDistance = calcDistanceBeeline(origin, destination) / 1000
            return routeFallbackFromDistance(mode, beelineDistance)
        }

        /**
         * @param mode Travel mode
         * @param distance Distance of trip. Unit: Kilometer
         */
        fun routeFallbackFromDistance(mode: Mode, distance: Double): Route {
            val time = when (mode) {
                Mode.PUBLIC_TRANSIT -> distance / 22.5 * 60 // 22.5 km/h
                Mode.FOOT -> distance / 5 * 60 // 5 km/h
                Mode.BICYCLE -> distance / 18 * 60 // 18 km/h
                else -> distance / 75  * 60 // 75 km/h + 5 min for Parking
            }
            return Route(distance, time, null, null)
        }

        fun sampleDistanceHomeHomeTrip(rng: Random): Double {
            val lambda = 1.0 / 12.0 // TODO
            return 1 / (-lambda) * ln(1-rng.nextDouble())
        }
    }
}