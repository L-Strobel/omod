package de.uniwuerzburg.omod.core.models

import org.locationtech.jts.geom.Coordinate

/**
 * Routing cell. Group of buildings used to faster calculate the approximate distance by car.
 *
 * @param id ID used for hashing
 * @param coord Coordinates of centroid in model CRS (Distance unit: meters)
 * @param latlonCoord Coordinates of centroid in lat-lon
 * @param buildings Buildings associated with the cell
 */
data class Cell (
    val id: Int,
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    val buildings: List<Building>,
) : RealLocation {
    // From LocationOption
    override val avgDistanceToSelf = buildings.map { it.coord.distance(coord) }.average()

    // Most common taz (Normally null here)
    override var odZone = buildings.groupingBy { it.odZone }.eachCount().maxByOrNull { it.value }!!.key

    override val inFocusArea = buildings.any { it.inFocusArea }

    override val attractions = buildings.map { it.attractions }
        .flatMap { map -> map.entries }
        .groupBy ({ it.key },{ it.value })
        .mapValues { it.value.sum() }

    override val population = buildings.sumOf { it.population }

    override fun hashCode(): Int {
        return id
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Cell

        if (id != other.id) return false
        if (coord != other.coord) return false
        if (latlonCoord != other.latlonCoord) return false
        if (buildings != other.buildings) return false
        if (avgDistanceToSelf != other.avgDistanceToSelf) return false
        if (odZone != other.odZone) return false
        if (inFocusArea != other.inFocusArea) return false
        if (attractions != other.attractions) return false
        if (population != other.population) return false

        return true
    }
}