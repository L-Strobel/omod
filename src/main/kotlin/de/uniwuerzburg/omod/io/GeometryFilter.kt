package de.uniwuerzburg.omod.io

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.openstreetmap.osmosis.areafilter.v0_6.AreaFilter
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.filter.common.IdTrackerType

/**
 * Osmosis filter. Returns all osm objects in a given area.
 *
 * @param area Geometry of the area. Must be in EPSG 4326, i.e. lat lon coordinates.
 * @param geometryFactory Factory for point creation in check. Precision should be consistent across an application
 * @param idTrackerType ID tracker. See Osmosis documentation.
 * @param clipIncompleteEntities Remove ways and relations that are only partly in the area
 * @param completeWays Include all nodes of ways even if the node is outside the area
 * @param completeRelations Include all nodes of relations even if the node is outside the area
 * @param cascadingRelations Include all relations that are included in a relation that is intersects the area
 */
class GeometryFilter (
    private val area: Geometry,
    private val geometryFactory: GeometryFactory,
    idTrackerType: IdTrackerType,
    clipIncompleteEntities: Boolean,
    completeWays: Boolean,
    completeRelations: Boolean,
    cascadingRelations: Boolean
) : AreaFilter(idTrackerType, clipIncompleteEntities, completeWays, completeRelations, cascadingRelations) {
    private val bounds: Envelope = area.envelopeInternal

    /**
     * Check if a node is inside the area.
     * @param node Node
     * @return true when the node is inside the area
     */
    override fun isNodeWithinArea(node: Node): Boolean {
        val coords = Coordinate(node.latitude, node.longitude)

        // Quickly check bounds
        return if (!bounds.contains(coords)) {
            false
        } else {
            // Rigorous check
            val point = geometryFactory.createPoint(coords)
            area.contains(point)
        }
    }
}