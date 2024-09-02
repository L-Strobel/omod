package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.ActivityType
import de.uniwuerzburg.omod.core.models.AggLocation
import de.uniwuerzburg.omod.core.models.LocationOption
import de.uniwuerzburg.omod.core.models.ODZone
import java.util.*

/**
 * Find the location of activities.
 */
interface DestinationFinder {
    /**
     * Determine the probabilistic weight that a location is a destination given an activity type but no origin
     * for all possible destinations.
     *
     * @param destinations Possible destinations
     * @param activityType Activity type conducted at the destination.
     * @return Probabilistic weights
     */
    fun getWeightsNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : List<Double>

    /**
     * Determine the probabilistic weight that a location is a destination given an origin and activity type
     * for all possible destinations.
     *
     * @param destinations Possible destinations
     * @param activityType Activity type conducted at the destination.
     * @return Probabilistic weights
     */
    fun getWeights(origin: LocationOption, destinations: List<LocationOption>, activityType: ActivityType ): List<Double>

    /**
     * Determine activity location.
     * To speed things up only the aggregated location is computed in with the distance factored in.
     * The building level location is determined based only on the attraction values of the buildings inside the
     * aggregated location.
     * @param origin Routing cell of the trip origin.
     * @param destinations Possible destinations
     * @param activityType Activity for which a location is searched
     * @param rng Random number generator
     * @return destination
     */
    fun getLocation(
        origin: AggLocation, destinations: List<AggLocation>,
        activityType: ActivityType, rng: Random
    ) : LocationOption

    /**
     * Calibrate the destination finder with a OD-Matrix.
     * @param zones Possible destinations (Should be all that this destination finder applies to).
     * @param odZones OD-Matrix
     */
    fun calibrate(zones: List<AggLocation>, odZones: List<ODZone>)
}
