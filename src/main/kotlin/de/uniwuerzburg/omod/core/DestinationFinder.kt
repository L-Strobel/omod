package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.ActivityType
import de.uniwuerzburg.omod.core.models.AggLocation
import de.uniwuerzburg.omod.core.models.LocationOption
import de.uniwuerzburg.omod.core.models.ODZone

interface DestinationFinder {
    fun getWeightsNoOrigin(destinations: List<LocationOption>, activityType: ActivityType) : List<Double>
    fun getWeights(origin: LocationOption, destinations: List<LocationOption>, activityType: ActivityType ): List<Double>
    fun getLocation(origin: AggLocation, destinations: List<AggLocation>, activityType: ActivityType) : LocationOption
    fun calibrate(zones: List<AggLocation>, odZones: List<ODZone>)
}
