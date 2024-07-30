package de.uniwuerzburg.omod.core.models

/**
 * Day types supported by OMOD
 */
enum class Weekday {
    MO, TU, WE, TH, FR, SA, SU, HO, UNDEFINED;

    /**
     * Order of days. Note:
     *  - the day after a Holiday is undefined.
     *  - the day after an undefined day is undefined.
     */
    fun next() : Weekday {
        return when(this) {
            MO -> TU
            TU -> WE
            WE -> TH
            TH -> FR
            FR -> SA
            SA -> SU
            SU -> MO
            HO -> UNDEFINED
            UNDEFINED -> UNDEFINED
        }
    }
}