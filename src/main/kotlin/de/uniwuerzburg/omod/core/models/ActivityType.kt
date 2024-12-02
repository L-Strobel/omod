package de.uniwuerzburg.omod.core.models

/**
 * Activity types.
 */
@Suppress("unused")
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;

    fun matSimName() : String {
        return when(this) {
            HOME -> "h"     // (h)ome
            WORK -> "w"     // (w)ork
            BUSINESS -> "w" // (w)ork
            SCHOOL -> "e"   // (e)ducation
            SHOPPING -> "s" // (s)hopping
            OTHER -> "l"    // (l)eisure
        }
    }
}