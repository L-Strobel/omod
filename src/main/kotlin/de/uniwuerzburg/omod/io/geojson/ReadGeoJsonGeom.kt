package de.uniwuerzburg.omod.io.geojson

import de.uniwuerzburg.omod.io.json.readJson
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

/**
 * Reads GeoJson file. Will only use geo-information.
 * Throws away property entries.
 *
 * If the property entries are required use de.uniwuerzburg.omod.io.json.ReadJson
 */
fun readGeoJsonGeom(areaFile: File, geometryFactory: GeometryFactory): Geometry {
    val areaColl: GeoJsonNoProperties = readJson(areaFile)
    return if (areaColl is GeoJsonFeatureCollectionNoProperties) {
        geometryFactory.createGeometryCollection(
            areaColl.features.map { it.geometry.toJTS(geometryFactory) }.toTypedArray()
        )
    } else {
        (areaColl as GeoJsonGeom).toJTS(geometryFactory)
    }
}