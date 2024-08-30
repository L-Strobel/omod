package de.uniwuerzburg.omod.core.models

/**
 * A trip.
 *
 * If a property is null it means it is not (yet) defined.
 *
 * @param distance Distance of the trip. Unit: Kilometer
 * @param time Duration of the trip. Unit: Minute
 * @param mode Mode of the trip
 * @param lats Coordinates of the trip path (only computed if --return_path_coords True)
 * @param lons Coordinates of the trip path (only computed if --return_path_coords True)
 */
data class Trip (
    var distance: Double? = null,    // Unit: Kilometer
    var time: Double? = null,        // Unit: Minute
    var mode: Mode = Mode.UNDEFINED,
    var lats: List<Double>? = null,
    var lons: List<Double>? = null
)
