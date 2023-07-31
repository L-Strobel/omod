package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.CRSTransformer
import kotlinx.serialization.decodeFromString
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

private val logger = LoggerFactory.getLogger("de.uniwuerzburg.omod.io")

/**
 * Gather and combine all necessary information about the model area.
 *
 * @param focusArea Focus area in latitude longitude crs
 * @param osmFile osm.pbf file that covers the model area
 * @param bufferRadius Distance that the focus area will be buffered with
 * @param transformer Used for CRS conversions
 * @param geometryFactory Geometry factory
 * @param censusFile Distribution of the population in the area
 * @return List of buildings with all necessary features
 */
fun buildArea(focusArea: Geometry, osmFile: File, bufferRadius: Double, transformer: CRSTransformer,
              geometryFactory: GeometryFactory, censusFile: File? = null) : GeoJsonFeatureCollection {
    logger.info("Start reading OSM-File... (If this is too slow use smaller .osm.pbf file)")
    val osmBuildings = readOSM(focusArea, osmFile, bufferRadius, geometryFactory, transformer)
    logger.info("OSM-File read!")

    // Add census data if available
    if (censusFile != null) {
        logger.info("Start reading census data...")
        // Spatial index
        val buildingsTree = HPRtree()
        for (building in osmBuildings) {
            buildingsTree.insert(building.geometry.envelopeInternal, building)
        }

        val censusData: GeoJsonFeatureCollection = json.decodeFromString(censusFile.readText(Charsets.UTF_8))

        for (censusEntree in censusData.features) {
            var population = (censusEntree.properties as GeoJsonCensusProperties).population
            val censusZone = transformer.toModelCRS( censusEntree.geometry.toJTS(geometryFactory) )

            val intersectingBuildings = buildingsTree.query(censusZone.envelopeInternal)
                .map { it as BuildingData }
                .filter { it.geometry.intersects(censusZone) }
                .shuffled()

            val populationPerBuilding = ceil(population / intersectingBuildings.count().toDouble())
            for (building in intersectingBuildings) {
                val populationBuilding = min(population, populationPerBuilding)
                building.population = (building.population ?: 0.0) + populationBuilding
                population -= populationBuilding
                if (population <= 0) {
                    break
                }
            }
        }
        logger.info("Census data read!")
    }

    // Convert to GeoJSON
    val collection = GeoJsonFeatureCollection(
        features = osmBuildings.map {
            val center = it.geometry.centroid
            val coords = transformer.toLatLon(center).coordinate
            val geometry = GeoJsonPoint(listOf(coords.y, coords.x))

            val properties = GeoJsonBuildingProperties(
                osm_id = it.osm_id,
                in_focus_area = it.inFocusArea,
                area = it.area,
                population = it.population,
                landuse = it.landuse,
                number_shops = it.nShops,
                number_offices = it.nOffices,
                number_schools = it.nSchools,
                number_universities = it.nUnis,
            )
            GeoJsonFeature(geometry = geometry, properties = properties)
        }
    )
    return collection
}