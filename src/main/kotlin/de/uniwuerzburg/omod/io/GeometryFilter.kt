package de.uniwuerzburg.omod.io

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.openstreetmap.osmosis.areafilter.v0_6.AreaFilter
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.filter.common.IdTrackerType

/**
 * Return all osm objects in given area. Does not filter bounds.
 * @param area Geometry of the area. Must be in EPSG 4326, i.e. lat lon coordinates.
 * @param geometryFactory Factory for point creation in check. Precision should be consistent across an application.
 * Other parameters are inherited from AreaFilter
 */
class GeometryFilter (
    private val area: Geometry,
    private val geometryFactory: GeometryFactory,
    idTrackerType: IdTrackerType, clipIncompleteEntities: Boolean, completeWays: Boolean,
    completeRelations: Boolean, cascadingRelations: Boolean
) : AreaFilter(idTrackerType, clipIncompleteEntities, completeWays, completeRelations, cascadingRelations) {
    private val bounds: Envelope = area.envelopeInternal

    override fun isNodeWithinArea(node: Node): Boolean {
        val coords = Coordinate(node.latitude, node.longitude)

        // Quickly check bounds
        return if (!bounds.contains(coords)) {
            false
        } else {
            // Thorough but slower check
            val point = geometryFactory.createPoint(coords)
            area.contains(point)
        }
    }
}