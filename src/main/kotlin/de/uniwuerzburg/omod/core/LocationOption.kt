package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.io.GeoJsonBuildingProperties
import de.uniwuerzburg.omod.io.GeoJsonFeatureCollection
import org.apache.commons.math3.ml.clustering.Clusterable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point

/**
 * Interface for all objects that can be the location of an activity.
 * Currently: routing cells, buildings, and dummy locations
 */
sealed interface LocationOption {
    val coord: Coordinate
    val latlonCoord: Coordinate
    var odZone: ODZone?
    val avgDistanceToSelf: Double
    val inFocusArea: Boolean
}

/**
 * A location that is inside the area with OSM data, i.e. not a dummy location
 */
interface RealLocation : LocationOption {
    val population: Double
    val attractions: Map<Int, Double>
}

/**
 * A building. Can be the location of an activity.
 *
 * @param osmID OpenStreetMap ID
 * @param coord Coordinates of centroid in model CRS (Distance unit: meters)
 * @param latlonCoord coordinates in lat-lon
 * @param odZone origin-destination zone (TAZ). Only relevant if OD-data is provided.
 * @param inFocusArea is the building inside the focus area?
 */
class Building  (
    @Suppress("unused")
    val osmID: Long,
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override var odZone: ODZone?,
    override val inFocusArea: Boolean,
    override val attractions: Map<Int, Double>,
    override val population: Double,
    val point: Point,
    var cell: Cell? = null
) : RealLocation, Clusterable {
    override val avgDistanceToSelf = 0.0

    companion object {
        /**
         * Create a list of buildings from a GeoJSON object.
         *
         * @param collection GeoJSON object
         * @param geometryFactory GeometryFactory
         * @param transformer Used for CRS conversion
         */
        fun fromGeoJson(collection: GeoJsonFeatureCollection, geometryFactory: GeometryFactory,
                        transformer: CRSTransformer,
                        dcFunctions: Map<ActivityType, LocationChoiceDCWeightFun>): List<Building> {
            return collection.features.map {
                require(it.properties is GeoJsonBuildingProperties) {
                    "Geo json contains features that are not buildings!"
                }

                val properties = it.properties
                val point = transformer.toModelCRS( it.geometry.toJTS(geometryFactory) ).centroid

                val attractions = dcFunctions.map { (_, v) -> v.getUniqueID() to v.calcAttraction(properties)}.toMap()

                Building(
                    osmID = properties.osm_id,
                    coord = point.coordinate,
                    latlonCoord = transformer.toLatLon(point).coordinate,
                    inFocusArea = properties.in_focus_area,
                    attractions = attractions,
                    population = properties.population ?: 0.0,
                    odZone = null,
                    point = point
                )
            }
        }
    }

    override fun getPoint(): DoubleArray {
        return arrayOf(coord.x, coord.y).toDoubleArray()
    }
}

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
    override val latlonCoord: Coordinate ,
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

/**
 * Dummy location. Used for locations outside the model area (focus area + buffer area).
 * These locations are introduced if an odFile is provided that contains TAZs that don't intersect the model area.
 *
 * @param coord Coordinates of centroid in model CRS (Distance unit: meters)
 * @param latlonCoord Coordinates of centroid in lat-lon
 * @param odZone The od-zone associated with the location
 * @param transferActivities Activities which can cause arrival and departure here.
 * Only activities defined in the odFile can cause an agent to arrive at a dummy location or leave it.
 */
data class DummyLocation (
    override val coord: Coordinate,
    override val latlonCoord: Coordinate,
    override var odZone: ODZone?,
    val transferActivities: Set<ActivityType>
) : LocationOption {
    override val inFocusArea = false
    override val avgDistanceToSelf = 1.0
}
