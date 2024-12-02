package de.uniwuerzburg.omod.core.models

import de.uniwuerzburg.omod.core.models.Sex.*

/**
 * Possible homogeneous groups
 */
enum class HomogeneousGrp {
    WORKING, NON_WORKING, PUPIL_STUDENT, UNDEFINED;

    fun matSimName() : String? {
        return when(this) {
            WORKING -> "yes"
            UNDEFINED -> null
            else -> "no"
        }
    }
}