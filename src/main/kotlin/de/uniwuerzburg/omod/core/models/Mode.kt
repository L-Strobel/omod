package de.uniwuerzburg.omod.core.models

/**
 * Possible travel modes. UNDEFINED is only used if no mode-choice is done at all.
 */
enum class Mode {
    CAR_DRIVER, CAR_PASSENGER, PUBLIC_TRANSIT, BICYCLE, FOOT, UNDEFINED;

    fun matSimName() : String {
        return when(this) {
            CAR_DRIVER -> "car"
            CAR_PASSENGER -> "car_passenger"
            PUBLIC_TRANSIT -> "pt"
            BICYCLE -> "bike"
            FOOT -> "walk"
            UNDEFINED -> "car"
        }
    }
}