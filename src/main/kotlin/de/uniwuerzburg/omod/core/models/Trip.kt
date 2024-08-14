package de.uniwuerzburg.omod.core.models

import java.time.Instant

data class Trip (
    val origin: LocationOption,
    val destination: LocationOption,
    var distance: Double,    // Unit: Kilometer
    var time: Double?,       // Unit: Minute
    var mode: Mode = Mode.UNDEFINED,
    var lats: List<Double>? = null,
    var lons: List<Double>? = null
)
