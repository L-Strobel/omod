package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.CRSTransformer
import de.uniwuerzburg.omod.core.Omod
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

internal class ReadOSMKtTest {
    /**
     * Test the osm.pbf reader
     */
    @Test
    fun readOSMTest() {
        val areaString = Omod::class.java.classLoader.getResource("test_area.geojson")!!.readText(Charsets.UTF_8)
        val osmFile = File(Omod::class.java.classLoader.getResource("test.osm.pbf")!!.file)
        val geometryFactory = GeometryFactory()

        val areaColl: GeoJsonNoProperties = json.decodeFromString(areaString)
        val focusArea = if (areaColl is GeoJsonFeatureCollectionNoProperties) {
            geometryFactory.createGeometryCollection(
                areaColl.features.map { it.geometry.toJTS(geometryFactory) }.toTypedArray()
            ).union()
        } else {
            (areaColl as GeoJsonGeometryCollection).toJTS(geometryFactory).union()
        }

        val transformer = CRSTransformer( focusArea.centroid.coordinate.y )
        val buildings = readOSM(focusArea, osmFile, 0.0, geometryFactory, transformer)

        assert(buildings.size == 5)
    }
}