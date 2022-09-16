package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

/**
 * Zone of the OD-Matrix
 */
data class ODZone (
    val name: String,
    val originActivity: ActivityType,
    val destinationActivity: ActivityType,
    val geometry: Geometry,
    var inFocusArea: Boolean = false,
) {
    // First: Destination zones. Second: the number of trips from here to there
    var destinations: List<Pair<ODZone, Double>> = listOf()

    val aggLocs: MutableList<AggregateLocation> = mutableListOf()

    companion object {
        fun readODMatrix(odFile: File, factory: GeometryFactory) : List<ODZone> {
            // Read OD
            val geoJson: GeoJsonFeatureCollection = Json { ignoreUnknownKeys = true }
                .decodeFromString(odFile.readText(Charsets.UTF_8))

            // Get zones
            val odZones = geoJson.features.map {
                val properties = it.properties as GeoJsonODProperties
                ODZone (
                    name = properties.origin,
                    originActivity = properties.origin_activity,
                    destinationActivity = properties.destination_activity,
                    geometry = it.geometry.toJTS(factory)
                )
            }

            // Add transitions
            val nameMapping = odZones.associateBy { it.name }
            for (entry in geoJson.features) {
                val properties = entry.properties as GeoJsonODProperties
                val originZone = nameMapping[properties.origin]!!
                originZone.destinations = properties.destinations.map { Pair(nameMapping[it.key]!!, it.value) }
            }

            // Check if OD is valid
            require(setOf(odZones.map { it.originActivity }).size == 1)
            { "All origin activities must be the same!" }
            require(setOf(odZones.map { it.destinationActivity }).size == 1)
            { "All destination activities must be the same!" }

            // Valid activities? Currently, only HOME->WORK are allowed
            require(odZones.all { it.originActivity == ActivityType.HOME })
            {
                "Only calibration with commuting data is currently supported! " +
                        "This means OD-Matrices with Activities HOME->WORK."
            }
            require(odZones.all { it.destinationActivity == ActivityType.WORK })
            {
                "Only calibration with commuting data is currently supported! " +
                        "This means OD-Matrices with Activities HOME->WORK."
            }
            return odZones
        }
    }
}
