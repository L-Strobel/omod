package de.uniwuerzburg.omod.core.models

import org.locationtech.jts.geom.Coordinate

/**
 * Dummy location. Used for locations outside the model area (focus area + buffer area).
 * These locations are introduced if an odFile is provided that contains TAZs that don't intersect the model area.
 *
 * @param coord Coordinates of centroid in model CRS (Distance unit: meters)
 * @param latlonCoord Coordinates of centroid in lat-lon
 * @param odZone The od-zone associated with the location
 * @param transferActivities Activities which can cause arrival and departure here.
 * Only activities defined in the odFile can cause an agent to arrive at a dummy location or leave it.
 */
data class DummyLocation (
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override var odZone: ODZone?,
    val transferActivities: Set<ActivityType>
) : LocationOption {
    override val inFocusArea = false
    override val avgDistanceToSelf = 1.0
}
