package de.uniwuerzburg.omod.core.models

import java.time.LocalTime

typealias TripVisitor = (
    trip: Trip, originActivity: Activity, destinationActivity: Activity,
    departureTime: LocalTime, departureWD: Weekday, finished: Boolean
) -> Unit

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
    val activities: List<Activity>,
) {
    var trips: List<Trip> = List(activities.size - 1) { Trip() }

    /**
     * Apply a function to every trip in the diary.
     * Use this over direct accesses to trips to get the start time of a trip.
     * This function is needed because the start times of all consecutive trips change then a trip is modified.
     *
     * Argument "finished": TRUE for the last trip in the diary else FALSE.
     *
     * @param visitor Function that determines what to do with every trip of the diary.
     * For example, changing the mode of that trip.
     */
    fun visitTrips(
        visitor: TripVisitor
    ){
        if (this.activities.size <= 1) {
            return
        }

        // Run through day
        var wd = this.dayType
        var currentActivity = this.activities.first()
        var currentMinute = 0.0
        for ((i, nextActivity) in this.activities.drop(1).withIndex()) {
            val trip = trips[i]
            currentMinute += currentActivity.stayTime ?: 0.0

            // Update Weekday
            var cnt = 0
            while (currentMinute >= 60 * 24) {
                wd = wd.next()
                currentMinute -= 60 * 24
                cnt += 1
            }

            // Departure time
            val currentTime = LocalTime.of(currentMinute.toInt() / 60, currentMinute.toInt() % 60)

            // Update trip
            visitor(trip, currentActivity, nextActivity, currentTime, wd, i>=trips.size)

            // Add estimated travel time
            currentMinute += trip.time ?: 0.0

            currentActivity = nextActivity
        }
    }
}