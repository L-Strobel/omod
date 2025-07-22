package de.uniwuerzburg.omod.io

import de.uniwuerzburg.omod.io.osm.BuildingData
import de.uniwuerzburg.omod.utils.CRSTransformer
import de.uniwuerzburg.omod.utils.fastCovers
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree

fun inFocusArea(buildings:List<BuildingData>, focusArea: Geometry,
                geometryFactory: GeometryFactory, transformer: CRSTransformer
){

    val buildingsTree = HPRtree()
    for (building in buildings) {
        buildingsTree.insert(building.geometry.envelopeInternal, building)
    }

    val utmFocusArea = transformer.toModelCRS(focusArea)
    fastCovers(
        utmFocusArea, listOf(10000.0, 5000.0, 1000.0), geometryFactory,
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