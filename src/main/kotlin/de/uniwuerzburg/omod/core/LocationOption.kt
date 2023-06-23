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

    val nBuilding: Double
    val nOffices: Double
    val nShops: Double
    val nSchools: Double
    val nUnis: Double
    val nResidential: Double
    val nCommercial: Double
    val nIndustrial: Double

    val areaResidential: Double
    val areaCommercial: Double
    val areaIndustrial: Double
    val areaOther: Double
}

/**
 * A building. Can be the location of an activity.
 *
 * @param osmID OpenStreetMap ID
 * @param coord Coordinates of centroid in model CRS (Distance unit: meters)
 * @param latlonCoord coordinates in lat-lon
 * @param odZone origin-destination zone (TAZ). Only relevant if OD-data is provided.
 * @param inFocusArea is the building inside the focus area?
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
) : RealLocation, Clusterable {
    override val avgDistanceToSelf = 0.0

    override val nBuilding = 1.0
    override val nResidential: Double
    override val nIndustrial: Double
    override val nCommercial: Double

    override val areaResidential: Double
    override val areaCommercial: Double
    override val areaIndustrial: Double
    override val areaOther: Double

    init {
        var nResidential = 0.0
        var nIndustrial = 0.0
        var nCommercial = 0.0

        var areaResidential = 0.0
        var areaCommercial = 0.0
        var areaIndustrial = 0.0
        var areaOther = 0.0

        when(landuse) {
            Landuse.RESIDENTIAL -> {
                nResidential += 1.0
                areaResidential = area
            }
            Landuse.COMMERCIAL -> {
                nCommercial = 1.0
                areaCommercial = area
            }
            Landuse.INDUSTRIAL -> {
                nIndustrial = 1.0
                areaIndustrial = area
            }
            else -> {
                areaOther = area
            }
        }

        this.nResidential = nResidential
        this.nIndustrial = nIndustrial
        this.nCommercial = nCommercial

        this.areaResidential = areaResidential
        this.areaCommercial = areaCommercial
        this.areaIndustrial = areaIndustrial
        this.areaOther = areaOther
    }

    companion object {
        /**
         * Create a list of buildings from a GeoJSON object.
         *
         * @param collection GeoJSON object
         * @param geometryFactory GeometryFactory
         * @param transformer Used for CRS conversion
         */
        fun fromGeoJson(collection: GeoJsonFeatureCollection, geometryFactory: GeometryFactory,
                        transformer: CRSTransformer): List<Building> {
            return collection.features.map {
                require(it.properties is GeoJsonBuildingProperties) {
                    "Geo json contains features that are not buildings!"
                }

                val properties = it.properties
                val point = transformer.toModelCRS( it.geometry.toJTS(geometryFactory) ).centroid

                Building(
                    osmID = properties.osm_id,
                    coord = point.coordinate,
                    latlonCoord = transformer.toLatLon(point).coordinate,
                    area = properties.area,
                    population = properties.population ?: 0.0,
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

    // Sum
    override val inFocusArea = buildings.any { it.inFocusArea }
    override val population = buildings.sumOf { it.population }

    override val nBuilding = buildings.sumOf { it.nBuilding }
    override val nOffices = buildings.sumOf { it.nOffices }
    override val nSchools = buildings.sumOf { it.nSchools }
    override val nUnis = buildings.sumOf { it.nUnis }
    override val nShops = buildings.sumOf { it.nShops }
    override val nResidential = buildings.sumOf { it.nResidential }
    override val nIndustrial = buildings.sumOf { it.nIndustrial }
    override val nCommercial = buildings.sumOf { it.nCommercial }

    override val areaResidential = buildings.sumOf { it.areaResidential }
    override val areaCommercial = buildings.sumOf { it.areaCommercial }
    override val areaIndustrial = buildings.sumOf { it.areaIndustrial }
    override val areaOther = buildings.sumOf { it.areaOther }

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
