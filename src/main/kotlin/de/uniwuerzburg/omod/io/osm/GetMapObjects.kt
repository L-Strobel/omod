package de.uniwuerzburg.omod.io.osm

import crosby.binary.osmosis.OsmosisReader
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.openstreetmap.osmosis.core.filter.common.IdTrackerType
import java.io.File
import java.io.FileInputStream

fun getMapObjects (
    fullArea: Geometry, osmFile: File, geometryFactory: GeometryFactory): MutableList<MapObject> {
    // Prepare osmosis pipeline
    val reader = OsmosisReader(FileInputStream(osmFile))
    val processor = OSMProcessor(IdTrackerType.Dynamic, geometryFactory)
    val geomFilter = GeometryFilter(
        fullArea,
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

    // Clean up
    processor.close()

    return processor.mapObjects
}
