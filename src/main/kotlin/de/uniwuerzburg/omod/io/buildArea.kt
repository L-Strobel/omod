package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.CRSTransformer
import kotlinx.serialization.decodeFromString
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import java.io.File

/**
 * @param area in latitude longitude format
 */
fun buildArea(area: Geometry, osmFile: File, bufferRadius: Double, transformer: CRSTransformer,
              geometryFactory: GeometryFactory, censusFile: File? = null) : GeoJsonFeatureCollection {
    val osmBuildings = readOSM(area, osmFile, bufferRadius, geometryFactory, transformer)

    // Add census data if available
    if (censusFile != null) {
        // Spatial index
        val buildingsTree = HPRtree()
        for (building in osmBuildings) {
            buildingsTree.insert(building.geometry.envelopeInternal, building)
        }

        val censusData: GeoJsonFeatureCollection = json.decodeFromString(censusFile.readText(Charsets.UTF_8))

        for (censusEntree in censusData.features) {
            val population = (censusEntree.properties as GeoJsonCensusProperties).population
            val censusZone = censusEntree.geometry.toJTS(geometryFactory, transformer)

            val intersectingBuildings = buildingsTree.query(censusZone.envelopeInternal)
                .map { it as BuildingData }
                .filter { it.geometry.intersects(censusZone) }

            for (building in intersectingBuildings) {
                building.population = population / intersectingBuildings.count().toDouble()
            }
        }
    }

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