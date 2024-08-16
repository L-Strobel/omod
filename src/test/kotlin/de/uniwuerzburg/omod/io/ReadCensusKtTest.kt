package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.Omod
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeatureCollectionNoProperties
import de.uniwuerzburg.omod.io.geojson.GeoJsonGeometryCollection
import de.uniwuerzburg.omod.io.geojson.GeoJsonNoProperties
import de.uniwuerzburg.omod.io.osm.BuildingData
import de.uniwuerzburg.omod.io.osm.readOSM
import de.uniwuerzburg.omod.utils.CRSTransformer
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.util.*

class ReadCensusKtTest {
    private val geometryFactory = GeometryFactory()

    private fun setup() : Pair<List<BuildingData>, CRSTransformer> {
        val areaString = Omod::class.java.classLoader.getResource("test_area.geojson")!!.readText(Charsets.UTF_8)
        val osmFile = File(Omod::class.java.classLoader.getResource("test.osm.pbf")!!.file)

        // Read OSM-File
        val areaColl: GeoJsonNoProperties = jsonHandler.decodeFromString(areaString)
        val focusArea = if (areaColl is GeoJsonFeatureCollectionNoProperties) {
            geometryFactory.createGeometryCollection(
                areaColl.features.map { it.geometry.toJTS(geometryFactory) }.toTypedArray()
            ).union()
        } else {
            (areaColl as GeoJsonGeometryCollection).toJTS(geometryFactory).union()
        }

        val transformer = CRSTransformer( focusArea.centroid.coordinate.y )
        val buildings = readOSM(focusArea, focusArea, osmFile, geometryFactory, transformer)
        return Pair(buildings, transformer)
    }

    /**
     * Test the ReadCensus
     */
    @Test
    fun readCensusPerfectFitTest() {
        val (buildings, transformer) = setup()
        val censusFile = File(Omod::class.java.classLoader.getResource("testCensusPerfectFit.geojson")!!.file)

        // Read Census file
        readCensus(buildings, transformer, geometryFactory, censusFile, Random())
        val popTstBuilding = buildings
            .filter { it.osm_id == 387201621.toLong() }
            .sumOf { it.population ?: 0.0 }.toInt()
        assert(popTstBuilding == 5)
    }

    @Test
    fun readCensusLooseTest() {
        val (buildings, transformer) = setup()
        val censusFile = File(Omod::class.java.classLoader.getResource("testCensusPerfectFit.geojson")!!.file)

        // Read Census file
        readCensus(buildings, transformer, geometryFactory, censusFile, Random())
        val popTstBuilding = buildings
            .filter { it.osm_id == 387201621.toLong() }
            .sumOf { it.population ?: 0.0 }.toInt()
        assert(popTstBuilding == 5)
    }
}