package de.uniwuerzburg.omod.io.osm

import de.uniwuerzburg.omod.io.inFocusArea
import de.uniwuerzburg.omod.io.logger
import de.uniwuerzburg.omod.utils.CRSTransformer
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

    // Add additional information to buildings
    for (building in buildings) {
        val extraInfos = extraInfoTree.query(building.geometry.envelopeInternal)
            .map { it as MapObject }
            .filter { it.geometry.intersects(building.geometry) }
        building.addInformation(extraInfos)
    }

    // Is building in focus area?
    inFocusArea(buildings, focusArea, geometryFactory, transformer)

    logger.info("OSM-File read!")
    return buildings
}

