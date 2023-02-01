package de.uniwuerzburg.omod.assignment

import com.graphhopper.GraphHopper
import de.uniwuerzburg.omod.core.LocationOption
import de.uniwuerzburg.omod.core.RealLocation
import de.uniwuerzburg.omod.routing.calcDistanceBeeline
import de.uniwuerzburg.omod.routing.routeWithCar

@Suppress("unused")
fun route(origin: LocationOption, destination: LocationOption, hopper: GraphHopper, withPath: Boolean = false,
          speedBeeline: Double = 130.0 / 3.6) : Route {
    return if ((origin !is RealLocation) or (destination !is RealLocation)) {
        val distance = calcDistanceBeeline(origin, destination)
        val time = distance / speedBeeline
        Route(distance, time, null, false)
    } else {
        Route.fromGH(routeWithCar(origin as RealLocation, destination as RealLocation, hopper), withPath)
    }
}
