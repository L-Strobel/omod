package de.uniwuerzburg.omod.io.geojson

import de.uniwuerzburg.omod.core.models.ActivityType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GeoJSON representation of an origin-destination matrix entry.
 *
 * @param origin Name of the origin
 * @param origin_activity Activity conducted at origin
 * @param destination_activity Activity conducted at destination
 * @param destinations Key: Name of destination, Value: Number of trips going to destination
 */
@Serializable
@SerialName("ODEntry")
data class GeoJsonODProperties (
    val origin: String,
    val origin_activity: ActivityType,
    val destination_activity: ActivityType,
    val destinations: Map<String, Double>
) : GeoJsonProperties()