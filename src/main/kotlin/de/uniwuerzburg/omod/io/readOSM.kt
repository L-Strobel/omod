package de.uniwuerzburg.omod.io

import crosby.binary.osmosis.OsmosisReader
import de.uniwuerzburg.omod.core.*
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import org.openstreetmap.osmosis.core.filter.common.IdTrackerType
import java.io.File
import java.io.FileInputStream


data class BuildingData (
    val osm_id: Long,
    val geometry: Geometry,
) {
    val area = geometry.area
    var landuse: Landuse = Landuse.NONE
    var nShops: Double = 0.0
    var nOffices: Double = 0.0
    var nSchools: Double = 0.0
    var nUnis: Double = 0.0
    var inFocusArea: Boolean = false
    var population: Double? = null
}

fun readOSM (area: Geometry, osmFile: File, bufferRadius: Double,
             geometryFactory: GeometryFactory): List<BuildingData> {
    val mercatorFocusArea = geometryFactory.createPolygon(
        area.convexHull().coordinates.map { latlonToMercator(it.x, it.y) }.toTypedArray()
    )
    val mercatorArea = mercatorFocusArea.buffer(bufferRadius)
    val latlonArea = geometryFactory.createPolygon(
        mercatorArea.coordinates.map { mercatorToLatLon(it.x, it.y) }.toTypedArray()
    )

    // Prepare osmosis pipeline
    val reader = OsmosisReader( FileInputStream(osmFile) )
    val processor = OSMProcessor(IdTrackerType.Dynamic, geometryFactory)
    val geomFilter = GeometryFilter(
        latlonArea,
        geometryFactory,
        IdTrackerType.Dynamic,
        clipIncompleteEntities = true,
        completeWays = false,
        completeRelations = false,
        cascadingRelations = false
    )
    geomFilter.setSink(processor)
    reader.setSink(geomFilter)

    // Read osm.pbf
    reader.run()

    // Filter objects and create spatial index
    val buildings = mutableListOf<BuildingData>()
    val extraInfoTree = HPRtree()
    for (mapObject in processor.mapObjects) {
        if (mapObject.type == MapObjectType.BUILDING) {
            buildings.add ( BuildingData(mapObject.id, mapObject.geometry) )
        } else {
            extraInfoTree.insert(mapObject.geometry.envelopeInternal, mapObject)
        }
    }

    // Add additional information to buildings
    for (building in buildings) {
        val extraInfos = extraInfoTree.query(building.geometry.envelopeInternal)
            .map { it as MapObject }
            .filter { it.geometry.intersects(building.geometry) }
        for (info in extraInfos) {
            when (info.type) {
                MapObjectType.SHOP              -> building.nShops += 1
                MapObjectType.OFFICE            -> building.nOffices += 1
                MapObjectType.SCHOOL            -> building.nSchools += 1
                MapObjectType.UNIVERSITY        -> building.nUnis += 1
                MapObjectType.LU_RESIDENTIAL    -> building.landuse = Landuse.RESIDENTIAL
                MapObjectType.LU_COMMERCIAL     -> building.landuse = Landuse.COMMERCIAL
                MapObjectType.LU_INDUSTRIAL     -> building.landuse = Landuse.INDUSTRIAL
                else -> { continue }
            }
        }
    }

    // Is building in focus area?
    val buildingsTree = HPRtree()
    for (building in buildings) {
        buildingsTree.insert(building.geometry.envelopeInternal, building)
    }

    fastCovers(mercatorFocusArea, listOf(10000.0, 5000.0, 1000.0), geometryFactory,
        ifNot = { },
        ifDoes = { e ->
            buildingsTree.query(e)
                .map { (it as BuildingData) }
                .forEach { it.inFocusArea = true }
        },
        ifUnsure = { e ->
            buildingsTree.query(e)
                .map { (it as BuildingData) }
                .filter { mercatorFocusArea.intersects(it.geometry) }
                .forEach { it.inFocusArea = true }
        }
    )
    return buildings
}