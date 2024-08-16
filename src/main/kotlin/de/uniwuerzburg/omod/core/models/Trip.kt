package de.uniwuerzburg.omod.core.models

data class Trip (
    var distance: Double? = null,    // Unit: Kilometer
    var time: Double? = null,        // Unit: Minute
    var mode: Mode = Mode.UNDEFINED,
    var lats: List<Double>? = null,
    var lons: List<Double>? = null
)
