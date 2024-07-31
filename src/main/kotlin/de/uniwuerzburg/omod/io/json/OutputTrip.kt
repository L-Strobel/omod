package de.uniwuerzburg.omod.io.json

import kotlinx.serialization.Serializable

/**
 * Output format of trip
 */
@Serializable
data class OutputTrip (
    val distance: Double,   // Unit: Meter
    val time: Double,       // Unit: Second
    val lats: List<Double>?,
    val lons: List<Double>?,
    val isReal: Boolean
)