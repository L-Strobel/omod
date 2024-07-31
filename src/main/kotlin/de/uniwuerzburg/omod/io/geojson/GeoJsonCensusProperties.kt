package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GeoJSON representation of a census field.
 *
 * @param population Population of geometry
 */
@Serializable
@SerialName("CensusEntry")
data class GeoJsonCensusProperties (
    val population: Double
) : GeoJsonProperties()