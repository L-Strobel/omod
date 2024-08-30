package de.uniwuerzburg.omod.core.models

/**
 * Possible travel modes. UNDEFINED is only used if no mode-choice is done at all.
 */
enum class Mode {
    CAR_DRIVER, CAR_PASSENGER, PUBLIC_TRANSIT, BICYCLE, FOOT, UNDEFINED;
}