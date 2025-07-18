package de.uniwuerzburg.omod.io.osm

import de.uniwuerzburg.omod.core.models.Landuse
import de.uniwuerzburg.omod.io.logger
import de.uniwuerzburg.omod.utils.CRSTransformer
import de.uniwuerzburg.omod.utils.fastCovers
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import java.io.File

/**
 * Read and process all the input files.
 *
 * @param focusArea Focus area
 * @param fullArea Buffered area
 * @param osmFile osm.pbf file
 * @param geometryFactory Geometry factory
 * @param transformer Used for CRS conversion
 * @return Data retrieved for all buildings in the model area
 */
fun readOSM (focusArea: Geometry, fullArea: Geometry, osmFile: File,
             geometryFactory: GeometryFactory, transformer: CRSTransformer
): List<BuildingData> {
    logger.info("Start reading OSM-File... (If this is too slow use smaller .osm.pbf file)")
    val mapObjects = getMapObjects(fullArea, osmFile, geometryFactory)

    // Filter objects, transform the coordinates, and create spatial index
    val buildings = mutableListOf<BuildingData>()
    val extraInfoTree = HPRtree()
    while (mapObjects.isNotEmpty()) {
        val mapObject = mapObjects.removeLast()
        val geom = transformer.toModelCRS(mapObject.geometry)
        if (mapObject.type == MapObjectType.BUILDING) {
            buildings.add ( BuildingData(mapObject.id, geom) )
        } else {
            val extraInfo = MapObject(mapObject.id, mapObject.type, geom)
            extraInfoTree.insert(geom.envelopeInternal, extraInfo)
        }
    }
    addBuildingInformation(buildings,extraInfoTree)



    logger.info("OSM-File read!")
    return buildings
}

fun addBuildingInformation( buildings:List<BuildingData>,extraInfoTree:HPRtree) {
// Add additional information to buildings
    for (building in buildings) {
        val extraInfos = extraInfoTree.query(building.geometry.envelopeInternal)
            .map { it as MapObject }
            .filter { it.geometry.intersects(building.geometry) }
        for (info in extraInfos) {
            when (info.type) {
                MapObjectType.SHOP -> building.nShops += 1
                MapObjectType.OFFICE -> building.nOffices += 1
                MapObjectType.SCHOOL -> building.nSchools += 1
                MapObjectType.UNIVERSITY -> building.nUnis += 1
                MapObjectType.RESTAURANT -> building.nRestaurant += 1
                MapObjectType.PLACE_OF_WORSHIP -> building.nPlaceOfWorship += 1
                MapObjectType.CAFE -> building.nCafe += 1
                MapObjectType.FAST_FOOD -> building.nFastFood += 1
                MapObjectType.KINDER_GARTEN -> building.nKinderGarten += 1
                MapObjectType.TOURISM -> building.nTourism += 1
                MapObjectType.LU_RESIDENTIAL -> building.landuse = Landuse.RESIDENTIAL
                MapObjectType.LU_COMMERCIAL -> building.landuse = Landuse.COMMERCIAL
                MapObjectType.LU_RETAIL -> building.landuse = Landuse.RETAIL
                MapObjectType.LU_INDUSTRIAL -> building.landuse = Landuse.INDUSTRIAL
                else -> {
                    continue
                }
            }
        }
    }
}

fun inFocusArea(buildings:List<BuildingData>,focusArea: Geometry,
                geometryFactory: GeometryFactory, transformer: CRSTransformer){
    // Is building in focus area?
    val buildingsTree = HPRtree()
    for (building in buildings) {
        buildingsTree.insert(building.geometry.envelopeInternal, building)
    }

    val utmFocusArea = transformer.toModelCRS(focusArea)
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
}