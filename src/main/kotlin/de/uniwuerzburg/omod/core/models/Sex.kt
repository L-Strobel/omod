package de.uniwuerzburg.omod.core.models

import de.uniwuerzburg.omod.core.models.ActivityType.*

enum class Sex {
    MALE, FEMALE, UNDEFINED;

    fun matSimName() : String? {
        return when(this) {
            MALE -> "m"
            FEMALE -> "f"
            UNDEFINED -> null
        }
    }
}