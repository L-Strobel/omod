package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.io.geojson.*
import de.uniwuerzburg.omod.io.geojson.property.CensusProperties
import de.uniwuerzburg.omod.io.json.readJsonStream
import de.uniwuerzburg.omod.io.osm.BuildingData
import de.uniwuerzburg.omod.utils.CRSTransformer
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import java.io.File
import java.util.*
import kotlin.math.ceil
import kotlin.math.min

/**
 * Add census data to buildings
 *
 * @param osmBuildings Formated OSM buildings (Output of ReadOSM)
 * @param transformer Used for CRS conversions
 * @param geometryFactory Geometry factory
 * @param censusFile Distribution of the population in the area
 * @return List of buildings with all necessary features
 */
fun readCensus(
    osmBuildings: List<BuildingData>, transformer: CRSTransformer,
    geometryFactory: GeometryFactory, censusFile: File,
    rng: Random
) : List<BuildingData> {

    logger.info("Start reading census data...")
    // Spatial index
    val buildingsTree = HPRtree()
    for (building in osmBuildings) {
        buildingsTree.insert(building.geometry.envelopeInternal, building)
    }

    val censusData: GeoJsonFeatureCollection<CensusProperties> = readJsonStream(censusFile)

    for (censusEntree in censusData.features) {
        var population = censusEntree.properties.population
        if (population <= 0) { continue }

        val censusZone = transformer.toModelCRS( censusEntree.geometry.toJTS(geometryFactory) )

        val intersectingBuildings = buildingsTree.query(censusZone.envelopeInternal)
            .map { it as BuildingData }
            .filter { it.geometry.intersects(censusZone) }
            .shuffled(rng)

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
    return osmBuildings
}