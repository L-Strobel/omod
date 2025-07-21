package de.uniwuerzburg.omod.io.osm

import de.uniwuerzburg.omod.core.models.Landuse
import org.locationtech.jts.geom.Geometry

/**
 * Data gathered about one building from all inputs.
 *
 * @param osm_id OSM ID of building
 * @param geometry Geometry of building
 */
data class BuildingData (
    val osm_id: Long,
    val geometry: Geometry,
) {
    val area = geometry.area
    var landuse: Landuse = Landuse.NONE
    var nShops: Double = 0.0
    var nOffices: Double = 0.0
    var nSchools: Double = 0.0
    var nUnis: Double = 0.0
    var nRestaurant: Double = 0.0
    var nPlaceOfWorship: Double = 0.0
    var nCafe: Double = 0.0
    var nFastFood: Double = 0.0
    var nKinderGarten: Double = 0.0
    var nTourism: Double = 0.0
    var inFocusArea: Boolean = false
    var population: Double? = null

    fun addInformation(infos: List<MapObject>) {
        for (info in infos) {
            when (info.type) {
                MapObjectType.SHOP -> nShops += 1
                MapObjectType.OFFICE -> nOffices += 1
                MapObjectType.SCHOOL -> nSchools += 1
                MapObjectType.UNIVERSITY -> nUnis += 1
                MapObjectType.RESTAURANT -> nRestaurant += 1
                MapObjectType.PLACE_OF_WORSHIP -> nPlaceOfWorship += 1
                MapObjectType.CAFE -> nCafe += 1
                MapObjectType.FAST_FOOD -> nFastFood += 1
                MapObjectType.KINDER_GARTEN -> nKinderGarten += 1
                MapObjectType.TOURISM -> nTourism += 1
                MapObjectType.LU_RESIDENTIAL -> landuse = Landuse.RESIDENTIAL
                MapObjectType.LU_COMMERCIAL -> landuse = Landuse.COMMERCIAL
                MapObjectType.LU_RETAIL -> landuse = Landuse.RETAIL
                MapObjectType.LU_INDUSTRIAL -> landuse = Landuse.INDUSTRIAL
                else -> {
                    continue
                }
            }
        }
    }
}