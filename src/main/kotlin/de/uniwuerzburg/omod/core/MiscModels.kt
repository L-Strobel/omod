package de.uniwuerzburg.omod.core

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
 * Landuse categories.
 */
enum class Landuse {
    RESIDENTIAL, INDUSTRIAL, COMMERCIAL, RETAIL, NONE;
}

/**
 * Activity types.
 */
@Suppress("unused")
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;
}

/**
 * Agent.
 *
 * @param id ID
 * @param homogenousGroup Hom. group of agent (Working person etc.)
 * @param mobilityGroup Mob. group of agent (Car user etc.)
 * @param age Age group of agent
 * @param home Home location of agent
 * @param work Work location of agent (Is also defined if the agent does not work)
 * @param school School location of agent (Is also defined if the agent does not go to school)
 * @param mobilityDemand Simulation result of agent
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
 * Simulation result. Daily activity dairy.
 *
 * @param day Number of the day the diary is for
 * @param dayType Type of day
 * @param activities Activities that the agent wants to conduct on the day
 */
data class Diary (
    val day: Int,
    val dayType: Weekday,
    val activities: List<Activity>
)

/**
 * Activity.
 *
 * @param type Type of the activity (HOME, WORK, SCHOOL, etc.)
 * @param stayTime Preferred duration of the activity. Unit: Minutes.
 * @param location Location where the activity takes place
 * @param lat Latitude of location
 * @param lon Longitude of location
 */
data class Activity (
    val type: ActivityType,
    val stayTime: Double?,
    val location: LocationOption,
    val lat: Double,
    val lon: Double,
    val cellId: Int
)

