package de.uniwuerzburg.omod.core.models

/**
 * Algorithms for determining mode choice.
 * NONE: Don't do mode choice
 * CAR_ONLY: Use the car for every trip
 * GTFS: Use a logit model to determine the mode. All modes defined in [de.uniwuerzburg.omod.core.models.Mode] are possible.
 */
enum class ModeChoiceOption {
    NONE, CAR_ONLY, GTFS
}