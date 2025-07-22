package de.uniwuerzburg.omod.core.models

/**
 * Possible age groups
 */
enum class AgeGrp {
    A0_40, A40_60, A60_100, UNDEFINED;

    companion object {
        fun fromInt(age: Int?) : AgeGrp {
            return if (age == null) {
                UNDEFINED
            } else if (age < 40) {
                A0_40
            } else if (age < 60) {
                A40_60
            } else {
                A60_100
            }
        }
    }
}