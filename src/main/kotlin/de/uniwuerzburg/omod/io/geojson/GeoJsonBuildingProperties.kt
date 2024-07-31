package de.uniwuerzburg.omod.io.geojson

import de.uniwuerzburg.omod.core.models.Landuse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GeoJSON representation of a building.
 *
 * @param osm_id OpenStreetMap ID
 * @param in_focus_area is the building inside the focus area?
 * @param area area of the building in meters
 * @param population population of building. Can be non-integer.
 * @param landuse OSM-Landuse of the building
 * @param number_shops Number of shops in the building
 * @param number_offices Number of offices in the building
 * @param number_schools Number of schools in the building
 * @param number_universities Number of universities in the building
 */
@Serializable
@SerialName("BuildingEntree")
data class GeoJsonBuildingProperties (
    val osm_id: Long,
    val in_focus_area: Boolean,
    val area: Double,
    val population: Double?,
    val landuse: Landuse,
    val number_shops: Double,
    val number_offices: Double,
    val number_schools: Double,
    val number_universities: Double,
    val number_place_of_worship: Double,
    val number_cafe: Double,
    val number_fast_food: Double,
    val number_kindergarten: Double,
    val number_tourism: Double,
) : GeoJsonProperties()