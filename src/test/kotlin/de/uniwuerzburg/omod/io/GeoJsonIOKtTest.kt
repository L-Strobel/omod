package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.core.Omod
import de.uniwuerzburg.omod.io.geojson.readGeoJsonGeom
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

internal class GeoJsonIOKtTest {
    private val geometryFactory = GeometryFactory()

    @Test
    fun readGeoJsonTestPoint() {
        val file = File(Omod::class.java.classLoader.getResource("geoJsonPointTest.geojson")!!.file)
        val geom = readGeoJsonGeom(file, geometryFactory)
        val coord = Coordinate( 48.76447901844995, 11.625266400072974)
        assert(geom == geometryFactory.createPoint( coord ))
    }

    @Test
    fun readGeoJsonTestPolygon() {
        val file = File(Omod::class.java.classLoader.getResource("geoJsonPolygonTest.geojson")!!.file)
        val geom = readGeoJsonGeom(file, geometryFactory)
        val coords = mutableListOf<Coordinate>()
        coords.add( Coordinate( 48.76447901844995, 11.625266400072974) )
        coords.add( Coordinate( 48.764015824711265, 11.624144613116698) )
        coords.add( Coordinate( 48.763647498930055, 11.624631426324328) )
        coords.add( Coordinate( 48.764158131675856, 11.625537322206782) )
        coords.add( Coordinate( 48.76447901844995, 11.625266400072974) )

        assert(geom == geometryFactory.createPolygon( coords.toTypedArray() ))
    }

    @Test
    fun readGeoJsonTestCollection() {
        val file = File(Omod::class.java.classLoader.getResource("geoJsonCollectionTest.geojson")!!.file)
        val geom = readGeoJsonGeom(file, geometryFactory)
        val coords = mutableListOf<Coordinate>()
        coords.add( Coordinate( 48.76447901844995, 11.625266400072974) )
        coords.add( Coordinate( 48.764015824711265, 11.624144613116698) )
        coords.add( Coordinate( 48.763647498930055, 11.624631426324328) )
        coords.add( Coordinate( 48.764158131675856, 11.625537322206782) )
        coords.add( Coordinate( 48.76447901844995, 11.625266400072974) )

        assert(geom == geometryFactory.createPolygon( coords.toTypedArray() ))
    }
}