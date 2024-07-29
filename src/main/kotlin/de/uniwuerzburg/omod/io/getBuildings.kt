package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.CRSTransformer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.math.min

private val logger = LoggerFactory.getLogger("de.uniwuerzburg.omod.io")

/**
 * Gather and combine all necessary information about the model area.
 * First checks if the data is cached and calls getBuildings().
 * Caches the data if it wasn't already.
 *
 * @param focusArea Focus area in latitude longitude crs
 * @param osmFile osm.pbf file that covers the model area
 * @param bufferRadius Distance that the focus area will be buffered with
 * @param transformer Used for CRS conversions
 * @param geometryFactory Geometry factory
 * @param censusFile Distribution of the population in the area
 * @param cacheDir Cache directory
 * @param cache IF false this function will always load the data anew.
 * @return List of buildings with all necessary features
 */
fun getBuildingsCachedWrapper(focusArea: Geometry, osmFile: File, bufferRadius: Double = 0.0,
                              transformer: CRSTransformer, geometryFactory: GeometryFactory,
                              censusFile: File?, cacheDir: Path, cache: Boolean
) : GeoJsonFeatureCollection {
    // Is cached?
    val bound = focusArea.envelopeInternal
    val cachePath = Paths.get(cacheDir.toString(),
        "AreaBounds${listOf(bound.minX, bound.maxX, bound.minY, bound.maxY)
            .toString().replace(" ", "")}" +
                "Buffer${bufferRadius}" +
                "Census${censusFile?.nameWithoutExtension ?: false}" +
                ".geojson"
    )

    // Check cache
    val collection: GeoJsonFeatureCollection
    if (cache and cachePath.toFile().exists()) {
        collection = json.decodeFromString(cachePath.toFile().readText(Charsets.UTF_8))
    } else {
        // Load data if not cached
        collection = getBuildings(
            focusArea = focusArea,
            osmFile = osmFile,
            bufferRadius = bufferRadius,
            censusFile = censusFile,
            transformer = transformer,
            geometryFactory = geometryFactory
        )

        if (cache) {
            Files.createDirectories(cachePath.parent)
            cachePath.toFile().writeText(json.encodeToString(collection))
        }
    }
    return collection
}

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
private fun getBuildings(focusArea: Geometry, osmFile: File, bufferRadius: Double, transformer: CRSTransformer,
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
        if (osmBuildings.sumOf { it.population ?: 0.0 } <= 0) {
            logger.warn("Population in model area is zero!")
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
                number_place_of_worship = it.nPlaceOfWorship,
                number_cafe = it.nCafe,
                number_fast_food = it.nFastFood,
                number_kindergarten = it.nKinderGarten,
                number_tourism = it.nTourism,
            )
            GeoJsonFeature(geometry = geometry, properties = properties)
        }
    )
    return collection
}