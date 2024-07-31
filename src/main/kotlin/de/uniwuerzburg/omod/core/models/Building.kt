package de.uniwuerzburg.omod.core.models

import de.uniwuerzburg.omod.core.*
import de.uniwuerzburg.omod.io.geojson.GeoJsonBuildingProperties
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeatureCollection
import de.uniwuerzburg.omod.utils.CRSTransformer
import org.apache.commons.math3.ml.clustering.Clusterable
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point

/**
 * A building. Can be the location of an activity.
 *
 * @param osmID OpenStreetMap ID
 * @param coord Coordinates of centroid in model CRS (Distance unit: meters)
 * @param latlonCoord coordinates in lat-lon
 * @param odZone origin-destination zone (TAZ). Only relevant if OD-data is provided.
 * @param inFocusArea is the building inside the focus area?
 * @param attractions Attraction value of that building for the distance choice function with id: KEY
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

                val attractions = dcFunctions.map { (_, v) -> v.id to v.calcAttraction(properties)}.toMap()

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

    override fun getAggLoc() : AggLocation? {
        return cell
    }
}