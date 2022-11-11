package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.io.GeoJsonBuildingProperties
import de.uniwuerzburg.omod.io.GeoJsonFeatureCollection
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point

/**
 * Interface for cells and buildings
 */
interface LocationOption {
    val coord: Coordinate
    val latlonCoord: Coordinate
    var odZone: ODZone?
    val avgDistanceToSelf: Double
    val inFocusArea: Boolean
    fun getPriorWeightFor(activityType: ActivityType) : Double
}

/**
 * A location that is inside the area with OSM data, i.e. not a dummy location
 */
interface RealLocation : LocationOption

/**
 * A location that is not a just a building, i.e. dummy location or cell
 */
interface AggregateLocation : LocationOption

/**
 * Model for a building
 *
 * @param coord coordinates in meters
 * @param area area of the building in meters
 * @param population population of building. Can be non-integer.
 * @param landuse OSM-Landuse of the building
 */
class Building  (
    @Suppress("unused")
    val osmID: Int,

    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override var odZone: ODZone?,
    override val inFocusArea: Boolean,

    val point: Point,

    val area: Double,
    val population: Double?,
    val landuse: Landuse,
    val nShops: Double,
    val nOffices: Double,
    val nSchools: Double,
    var cell: Cell? = null
) : LocationOption, RealLocation {
    override val avgDistanceToSelf = 0.0

    override fun getPriorWeightFor(activityType: ActivityType): Double {
        return when(activityType) {
            ActivityType.HOME -> population ?: 1.0
            ActivityType.WORK -> nShops + nOffices + landuse.getWorkWeight()
            ActivityType.SCHOOL -> nSchools
            ActivityType.SHOPPING -> nShops
            else -> 1.0
        }
    }

    companion object {
        fun fromGeoJson(collection: GeoJsonFeatureCollection, geometryFactory: GeometryFactory): List<Building> {
            return collection.features.map {
                require(it.properties is GeoJsonBuildingProperties) {
                    "Geo json contains features that are not buildings!"
                }

                val properties = it.properties
                val point = it.geometry.toJTS(geometryFactory).centroid

                Building(
                    osmID = properties.osm_id,
                    coord = point.coordinate,
                    latlonCoord = mercatorToLatLon(point.coordinate.x, point.coordinate.y),
                    area = properties.area,
                    population = properties.population,
                    landuse = Landuse.valueOf(properties.landuse),
                    nShops = properties.number_shops,
                    nOffices = properties.number_offices,
                    nSchools = properties.number_schools,
                    // nUnis = properties.number_universities,
                    inFocusArea = properties.in_focus_area,
                    odZone = null,
                    point = point
                )
            }
        }
    }
}

/**
 * Group of buildings. For faster 2D distribution sampling.
 * Method: Sample from Cells -> Sample from buildings in cell
 */
data class Cell (
    val id: Int,
    override val coord: Coordinate,

    val envelope: Envelope,
    val buildings: List<Building>,
) : LocationOption, RealLocation, AggregateLocation {
    // From LocationOption
    override val avgDistanceToSelf = buildings.map { it.coord.distance(coord) }.average()

    override val latlonCoord: Coordinate = mercatorToLatLon(coord.x, coord.y)

    // Most common taz (Normally null here)
    override var odZone = buildings.groupingBy { it.odZone }.eachCount().maxByOrNull { it.value }!!.key

    // Sum
    val homeWeight = buildings.sumOf { it.getPriorWeightFor(ActivityType.HOME) }
    val workWeight = buildings.sumOf { it.getPriorWeightFor(ActivityType.WORK) }
    val schoolWeight = buildings.sumOf { it.getPriorWeightFor(ActivityType.SCHOOL) }
    val shoppingWeight = buildings.sumOf { it.getPriorWeightFor(ActivityType.SHOPPING) }
    val otherWeight = buildings.sumOf { it.getPriorWeightFor(ActivityType.OTHER) }

    override val inFocusArea = buildings.any { it.inFocusArea }

    override fun hashCode(): Int {
        return id
    }

    override fun getPriorWeightFor(activityType: ActivityType): Double {
        return when(activityType) {
            ActivityType.HOME -> homeWeight
            ActivityType.WORK -> workWeight
            ActivityType.SCHOOL -> schoolWeight
            ActivityType.SHOPPING -> shoppingWeight
            else -> otherWeight
        }
    }

    /**
     * Auto generated equals
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Cell

        if (id != other.id) return false
        if (coord != other.coord) return false
        if (envelope != other.envelope) return false
        if (buildings != other.buildings) return false
        if (avgDistanceToSelf != other.avgDistanceToSelf) return false
        if (latlonCoord != other.latlonCoord) return false
        if (odZone != other.odZone) return false
        if (homeWeight != other.homeWeight) return false
        if (workWeight != other.workWeight) return false
        if (schoolWeight != other.schoolWeight) return false
        if (shoppingWeight != other.shoppingWeight) return false
        if (otherWeight != other.otherWeight) return false
        if (inFocusArea != other.inFocusArea) return false

        return true
    }
}

/**
 * Dummy location for far away locations. Used for commuting flows.
 */
data class DummyLocation (
    override val coord: Coordinate,
    val homeWeight: Double,
    val workWeight: Double,
    val schoolWeight: Double,
    val shoppingWeight: Double,
    val otherWeight: Double,
    override var odZone: ODZone?
) : LocationOption, AggregateLocation {
    override val avgDistanceToSelf = 1.0 // Number irrelevant as long as it is > 0
    override val latlonCoord = mercatorToLatLon(coord.x, coord.y)
    override val inFocusArea = false

    override fun getPriorWeightFor(activityType: ActivityType): Double {
        return when(activityType) {
            ActivityType.HOME -> homeWeight
            ActivityType.WORK -> workWeight
            ActivityType.SCHOOL -> schoolWeight
            ActivityType.SHOPPING -> shoppingWeight
            else -> otherWeight
        }
    }
}
