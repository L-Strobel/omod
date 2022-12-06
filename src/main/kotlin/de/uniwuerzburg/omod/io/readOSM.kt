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
             geometryFactory: GeometryFactory, transformer: CRSTransformer): List<BuildingData> {
    val utmFocusArea = transformer.toModelCRS(area)
    val utmArea = utmFocusArea.buffer(bufferRadius).convexHull()

    // Prepare osmosis pipeline
    val reader = OsmosisReader( FileInputStream(osmFile) )
    val processor = OSMProcessor(IdTrackerType.Dynamic, geometryFactory)
    val geomFilter = GeometryFilter(
        transformer.toLatLon(utmArea),
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

    // Filter objects, transform the coordinates, and create spatial index
    val buildings = mutableListOf<BuildingData>()
    val extraInfoTree = HPRtree()
    for (mapObject in processor.mapObjects) {
        val geom = transformer.toModelCRS(mapObject.geometry)
        if (mapObject.type == MapObjectType.BUILDING) {
            buildings.add ( BuildingData(mapObject.id, geom) )
        } else {
            val extraInfo = MapObject(mapObject.id, mapObject.type, geom)
            extraInfoTree.insert(geom.envelopeInternal, extraInfo)
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

    fastCovers(utmFocusArea, listOf(10000.0, 5000.0, 1000.0), geometryFactory,
        ifNot = { },
        ifDoes = { e ->
            buildingsTree.query(e)
                .map { (it as BuildingData) }
                .forEach { it.inFocusArea = true }
        },
        ifUnsure = { e ->
            buildingsTree.query(e)
                .map { (it as BuildingData) }
                .filter { utmFocusArea.intersects(it.geometry) }
                .forEach { it.inFocusArea = true }
        }
    )
    return buildings
}