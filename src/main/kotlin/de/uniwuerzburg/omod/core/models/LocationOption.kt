package de.uniwuerzburg.omod.core.models

import org.locationtech.jts.geom.Coordinate

/**
 * Interface for all objects that can be the location of an activity.
 * Currently: routing cells, buildings, and dummy locations
 */
sealed interface LocationOption {
    val coord: Coordinate
    val latlonCoord: Coordinate
    var odZone: ODZone?
    val avgDistanceToSelf: Double
    val inFocusArea: Boolean
}