package de.uniwuerzburg.omod.core.models

data class Trip (
    val mode: Mode,
    val distance: Double,   // Unit: Meter
    val time: Double?,       // Unit: Second
    val lats: List<Double>?,
    val lons: List<Double>?,
    val isReal: Boolean
)
