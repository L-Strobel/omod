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
    val nCommercial: Double
    val nIndustrial: Double

    val areaResidential: Double
    val areaCommercial: Double
    val areaIndustrial: Double
    val areaOther: Double
}

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
) : RealLocation {
    override val avgDistanceToSelf = 0.0

    override val nBuilding = 1.0
    override val nIndustrial: Double
    override val nCommercial: Double

    override val areaResidential: Double
    override val areaCommercial: Double
    override val areaIndustrial: Double
    override val areaOther: Double

    init {
        var nIndustrial = 0.0
        var nCommercial = 0.0

        var areaResidential = 0.0
        var areaCommercial = 0.0
        var areaIndustrial = 0.0
        var areaOther = 0.0

        when(landuse) {
            Landuse.RESIDENTIAL -> {
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

        this.nIndustrial = nIndustrial
        this.nCommercial = nCommercial

        this.areaResidential = areaResidential
        this.areaCommercial = areaCommercial
        this.areaIndustrial = areaIndustrial
        this.areaOther = areaOther
    }

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
    override var odZone: ODZone?,
    val transferActivities: Set<ActivityType> // Activities which can cause arrival and departure here
) : LocationOption {
    override val inFocusArea = false
    override val avgDistanceToSelf = 1.0
}
