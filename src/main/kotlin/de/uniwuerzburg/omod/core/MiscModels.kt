package de.uniwuerzburg.omod.core

/**
Possible days.
 */
enum class Weekday {
    MO, TU, WE, TH, FR, SA, SO, HO, UNDEFINED;

    fun next() : Weekday {
        return when(this) {
            MO -> TU
            TU -> WE
            WE -> TH
            TH -> FR
            FR -> SA
            SA -> SO
            SO -> MO
            HO -> UNDEFINED
            UNDEFINED -> UNDEFINED
        }
    }
}

/**
 * Possible homogeneous groups
 */
enum class HomogeneousGrp {
    WORKING, NON_WORKING, PUPIL_STUDENT, UNDEFINED;
}

/**
 * Possible mobility groups
 */
enum class MobilityGrp {
    CAR_USER, CAR_MIXED, NOT_CAR, UNDEFINED;
}

/**
 * Possible age groups
 */
@Suppress("unused")
enum class AgeGrp {
    A0_40, A40_60, A60_100, UNDEFINED;
}

/**
 * Landuse categories. For work and home probabilities
 */
enum class Landuse {
    RESIDENTIAL, INDUSTRIAL, COMMERCIAL, NONE;
}

/**
 * Activity types.
 */
@Suppress("unused")
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;
}

/**
 * Agent
 */
data class MobiAgent (
    val id: Int,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: AgeGrp,
    val home: LocationOption,
    val work: LocationOption,
    val school: LocationOption,
    val mobilityDemand: MutableList<Diary> = mutableListOf()
)

/**
 * Daily activity dairy
 */
data class Diary (
    val day: Int,
    val dayType: Weekday,
    val activities: List<Activity>
)


/**
 * Activity
 */
data class Activity (
    val type: ActivityType,
    val stayTime: Double?,
    val location: LocationOption,
    val lat: Double,
    val lon: Double
)
