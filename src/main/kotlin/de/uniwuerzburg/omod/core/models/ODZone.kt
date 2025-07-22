package de.uniwuerzburg.omod.core.models

import de.uniwuerzburg.omod.utils.CRSTransformer
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeatureCollection
import de.uniwuerzburg.omod.io.geojson.property.ODProperties
import kotlinx.serialization.json.Json
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

/**
 * Zone of the OD-Matrix, i.e. TAZ. Used for calibrating OMOD then an od-file is provided.
 * OD-Matrices are defined with a specific origin destination activity relationship.
 * For example, Home -> Work.
 *
 * @param name Name of the zone
 * @param originActivity Activity that is conducted at the origin.
 * @param destinationActivity Activity conducted at the destination.
 * @param geometry The geometry of the zone
 * @param inFocusArea Zone inside the focus area?
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

    val aggLocs: MutableList<LocationOption> = mutableListOf()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read od-file
         *
         * @param odFile GeoJSON file containing the od-matrix
         * @param factory GeometryFactory
         * @param transformer Transformer for CRS conversion
         * @return list of OD-Zones
         */
        fun readODMatrix(odFile: File, factory: GeometryFactory, transformer: CRSTransformer) : List<ODZone> {
            // Read OD
            val geoJson: GeoJsonFeatureCollection<ODProperties> = json.decodeFromString(odFile.readText(Charsets.UTF_8))

            // Get zones
            val odZones = geoJson.features.map {
                val properties = it.properties
                ODZone(
                    name = properties.origin,
                    originActivity = properties.origin_activity,
                    destinationActivity = properties.destination_activity,
                    geometry = transformer.toModelCRS(it.geometry.toJTS(factory))
                )
            }

            // Add transitions
            val nameMapping = odZones.associateBy { it.name }
            for (entry in geoJson.features) {
                val properties = entry.properties
                val originZone = nameMapping[properties.origin]!!
                originZone.destinations = properties.destinations
                    .filter { it.value > 0 }.map {Pair(nameMapping[it.key]!!, it.value)}
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