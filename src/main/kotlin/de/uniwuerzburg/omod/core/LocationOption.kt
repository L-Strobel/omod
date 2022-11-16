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
    val nBuilding: Double
    val nOffices: Double
    val nShops: Double
    val nSchools: Double
    val nUnis: Double
    val population: Double
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
    val osmID: Long,

    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override var odZone: ODZone?,
    override val inFocusArea: Boolean,

    override val nShops: Double,
    override val nOffices: Double,
    override val nSchools: Double,
    override val nUnis: Double,
    override val population: Double,

    val point: Point,
    val area: Double,
    val landuse: Landuse,

    var cell: Cell? = null
) : LocationOption, RealLocation {
    override val avgDistanceToSelf = 0.0
    override val nBuilding = 1.0

    companion object {
        fun fromGeoJson(collection: GeoJsonFeatureCollection, geometryFactory: GeometryFactory,
                        transformer: CRSTransformer): List<Building> {
            return collection.features.map {
                require(it.properties is GeoJsonBuildingProperties) {
                    "Geo json contains features that are not buildings!"
                }

                val properties = it.properties
                val point = it.geometry.toJTS(geometryFactory, transformer).centroid

                Building(
                    osmID = properties.osm_id,
                    coord = point.coordinate,
                    latlonCoord = transformer.toLatLon(point).coordinate,
                    area = properties.area,
                    population = properties.population ?: 1.0,
                    landuse = properties.landuse,
                    nShops = properties.number_shops,
                    nOffices = properties.number_offices,
                    nSchools = properties.number_schools,
                    nUnis = properties.number_universities,
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
    override val latlonCoord: Coordinate ,
    val envelope: Envelope,
    val buildings: List<Building>,
) : LocationOption, RealLocation, AggregateLocation {
    // From LocationOption
    override val avgDistanceToSelf = buildings.map { it.coord.distance(coord) }.average()

    // Most common taz (Normally null here)
    override var odZone = buildings.groupingBy { it.odZone }.eachCount().maxByOrNull { it.value }!!.key

    // Sum
    override val nBuilding = buildings.sumOf { it.nBuilding }
    override val population = buildings.sumOf { it.population }
    override val nOffices = buildings.sumOf { it.nOffices }
    override val nSchools = buildings.sumOf { it.nSchools }
    override val nUnis = buildings.sumOf { it.nUnis }
    override val nShops = buildings.sumOf { it.nShops }

    override val inFocusArea = buildings.any { it.inFocusArea }

    override fun hashCode(): Int {
        return id
    }

    // Auto generated
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Cell

        if (id != other.id) return false
        if (coord != other.coord) return false
        if (latlonCoord != other.latlonCoord) return false
        if (envelope != other.envelope) return false
        if (buildings != other.buildings) return false
        if (avgDistanceToSelf != other.avgDistanceToSelf) return false
        if (odZone != other.odZone) return false
        if (nBuilding != other.nBuilding) return false
        if (population != other.population) return false
        if (nOffices != other.nOffices) return false
        if (nSchools != other.nSchools) return false
        if (nUnis != other.nUnis) return false
        if (nShops != other.nShops) return false
        if (inFocusArea != other.inFocusArea) return false

        return true
    }
}

/**
 * Dummy location for far away locations. Used for commuting flows.
 */
data class DummyLocation (
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override val population: Double,
    override var odZone: ODZone?,
    val transferActivities: Set<ActivityType> // Activities which can cause arrival and departure here
) : LocationOption, AggregateLocation {
    // IF numbers get multiplied with k-factor later.
    // Therefore, the only real distinction is between 0 and >0
    override val nBuilding = 1.0 // If zero: location unreachable
    override val avgDistanceToSelf = 1.0 // Must be >0

    // These don't matter. Only exist for LocationOption interface.
    override val nOffices = 0.0
    override val nSchools = 0.0
    override val nUnis = 0.0
    override val nShops = 0.0

    override val inFocusArea = false
}
