package de.uniwuerzburg.omod.io

import org.locationtech.jts.geom.*
import org.locationtech.jts.operation.linemerge.LineMerger
import org.openstreetmap.osmosis.core.container.v0_6.*
import org.openstreetmap.osmosis.core.domain.v0_6.*
import org.openstreetmap.osmosis.core.filter.common.IdTracker
import org.openstreetmap.osmosis.core.filter.common.IdTrackerFactory
import org.openstreetmap.osmosis.core.filter.common.IdTrackerType
import org.openstreetmap.osmosis.core.store.IndexedObjectStore
import org.openstreetmap.osmosis.core.store.IndexedObjectStoreReader
import org.openstreetmap.osmosis.core.store.SingleClassObjectSerializationFactory
import org.openstreetmap.osmosis.core.task.v0_6.Sink

/**
 * Enumeration of all OSM map objects used in OMOD
 */
enum class MapObjectType {
    BUILDING, OFFICE, SHOP, SCHOOL, UNIVERSITY, KINDER_GARTEN,
    FAST_FOOD, PLACE_OF_WORSHIP, RESTAURANT, CAFE, TOURISM,
    LU_RESIDENTIAL, LU_COMMERCIAL, LU_RETAIL, LU_INDUSTRIAL
}

/**
 * OSM map object
 * @param id OSM ID
 * @param type Description of what the geometry represents
 * @param geometry Geometry
 */
class MapObject (
    val id: Long,
    val type: MapObjectType,
    val geometry: Geometry
)

/**
 * Maps OSM landuse tags to OMODs landuse categories
 * @param tag OSM tag
 * @return landuse category
 */
private fun getShortLanduseDescription(tag: String): MapObjectType? {
    return when(tag) {
        "residential"       -> MapObjectType.LU_RESIDENTIAL
        "commercial"        -> MapObjectType.LU_COMMERCIAL
        "retail"            -> MapObjectType.LU_RETAIL
        "industrial"        -> MapObjectType.LU_INDUSTRIAL
        /* Unused landuse types
        "cemetery"          -> MapObjectType.LU_RECREATIONAL
        "meadow"            -> MapObjectType.LU_RECREATIONAL
        "grass"             -> MapObjectType.LU_RECREATIONAL
        "park"              -> MapObjectType.LU_RECREATIONAL
        "recreation_ground" -> MapObjectType.LU_RECREATIONAL
        "allotments"        -> MapObjectType.LU_RECREATIONAL
        "scrub"             -> MapObjectType.LU_RECREATIONAL
        "heath"             -> MapObjectType.LU_RECREATIONAL
        "farmland"          -> MapObjectType.LU_AGRICULTURE
        "farmyard"          -> MapObjectType.LU_AGRICULTURE
        "orchard"           -> MapObjectType.LU_AGRICULTURE
        "forest"            -> MapObjectType.LU_FOREST
        "quarry"            -> MapObjectType.LU_FOREST
        "military"          -> MapObjectType.LU_NONE
         */
        else -> null
    }
}

/**
 * Find all the entities with the right keys and tags in the area.
 *
 * @param idTrackerType ID tracker. See Osmosis documentation
 * @param geometryFactory Geometry factory
 *
 * @property mapObjects The result. Access this after running the pipeline.
 */
class OSMProcessor(idTrackerType: IdTrackerType,
                   private val geometryFactory: GeometryFactory
) : EntityProcessor, Sink {
    val mapObjects = mutableListOf<MapObject>()

    // Storage of relevant nodes, ways, and relations (on disc)
    private val allNodes: IndexedObjectStore<NodeContainer> = IndexedObjectStore<NodeContainer>(
        SingleClassObjectSerializationFactory(NodeContainer::class.java), "afn"
    )
    private val allWays: IndexedObjectStore<WayContainer> = IndexedObjectStore<WayContainer>(
        SingleClassObjectSerializationFactory(WayContainer::class.java), "afw"
    )
    private val allRelations: IndexedObjectStore<RelationContainer> = IndexedObjectStore<RelationContainer>(
        SingleClassObjectSerializationFactory(RelationContainer::class.java), "afr"
    )
    private val relevantNodes = IdTrackerFactory.createInstance(idTrackerType)
    private val relevantWays = IdTrackerFactory.createInstance(idTrackerType)
    private val relevantRelations = IdTrackerFactory.createInstance(idTrackerType)

    override fun initialize(metaData: MutableMap<String, Any>?) {}

    /**
     * Called when pipeline yields an EntityContainer.
     */
    override fun process(entityContainer: EntityContainer?) {
        entityContainer?.process(this)
    }

    /**
     * Called when pipeline yields a NodeContainer.
     */
    override fun process(nodeContainer: NodeContainer) {
        val node = nodeContainer.entity

        // Remember for way and relation parsing
        allNodes.add(node.id, nodeContainer)

        // Is entity a relevant map object?
        if (relevantObject(node)) {
            relevantNodes.set(node.id)
        }
    }

    /**
     * Called when pipeline yields a WayContainer.
     */
    override fun process(wayContainer: WayContainer) {
        val way = wayContainer.entity

        // Remember for way and relation parsing
        allWays.add(way.id, wayContainer)

        // Is entity a relevant map object?
        if (relevantObject(way)) {
            relevantWays.set(way.id)
        }
    }

    /**
     * Called when pipeline yields a RelationContainer.
     */
    override fun process(relationContainer: RelationContainer) {
        val relation = relationContainer.entity

        // Remember for way and relation parsing
        allRelations.add(relation.id, relationContainer)

        // Is entity a relevant map object?
        if (relevantObject(relation)) {
            relevantRelations.set(relation.id)
        }
    }

    override fun process(boundContainer: BoundContainer) { }

    /**
     * Transform OSM node to JTS point
     * @param node OSM node
     * @return JTS Point
     */
    private fun getGeom(node: Node) : Point {
        val coords = Coordinate(node.latitude, node.longitude)
        val geometry = geometryFactory.createPoint(coords)

        if (!geometry.isValid) {
            throw Error("Faulty geometry found: ${geometry.toText()}")
        }

        return geometry
    }

    /**
     * Transform OSM way to JTS geometry
     * @param way OSM way
     * @param nodeReader Access to the nodes stored on disc in the process() step
     * @return JTS geometry
     */
    private fun getGeom(way: Way, nodeReader: IndexedObjectStoreReader<NodeContainer>) : Geometry {
        val coords = Array(way.wayNodes.size) { i ->
            val id = way.wayNodes[i].nodeId
            val node = nodeReader.get(id).entity
            Coordinate(node.latitude, node.longitude)
        }

        if (coords.size == 1) {
            return geometryFactory.createPoint(coords[0])
        }

        val line = geometryFactory.createLineString(coords)

        val geometry = if (line.isRing && coords.size > 2) {
            geometryFactory.createPolygon(line.coordinates)
        } else if (line.isRing) {
            line.startPoint // A ring with size 2 or less might as well be point
        } else {
            line
        }

        if (!geometry.isValid) {
            throw Error("Faulty geometry found: ${geometry.toText()}")
        }

        return geometry
    }

    /**
     * Transform OSM relation to JTS geometry
     * @param relation OSM relation
     * @param nodeReader Access to the osm nodes stored on disc in the process() step
     * @param wayReader Access to the osm ways stored on disc in the process() step
     * @return JTS geometry
     */
    private fun getGeom(relation: Relation,
                        wayReader: IndexedObjectStoreReader<WayContainer>,
                        nodeReader: IndexedObjectStoreReader<NodeContainer>
    ) : Geometry {
        // Find rings in the relation. Nodes and loose lines are currently ignored
        val outerRings = relationGetRings(relation, "outer", wayReader, nodeReader)
        val innerRings = relationGetRings(relation, "inner", wayReader, nodeReader)

        val mutInnerRings = innerRings.toMutableSet()

        val polygons = mutableSetOf<Polygon>()
        for (outerRing in outerRings) {
            val polygon = geometryFactory.createPolygon(outerRing)
            val holes = mutableSetOf<LinearRing>()

            // Check if inner ring is a hole in this polygon
            for (innerRing in mutInnerRings) {
                if (innerRing.within(polygon)) {
                    holes.add( innerRing )
                }
            }
            mutInnerRings.removeAll(holes)

            val holesAsPolygon = holes.map { geometryFactory.createPolygon(it) }.toTypedArray()
            val hole = geometryFactory.createMultiPolygon(holesAsPolygon).union()

            polygons.add ( polygon.difference(hole) as Polygon )
        }
        val geometry = geometryFactory.createMultiPolygon(polygons.toTypedArray()).union()

        if (!geometry.isValid) {
            throw Error("Faulty geometry found: ${geometry.toText()}")
        }

        return geometry
    }

    /**
     * Find linear rings in the relation that have a given osm memberRole.
     *
     * @param relation OSM relation
     * @param role Member role. Either "outer" to obtain exterior boundaries or "inner" to obtain the holes.
     * @param nodeReader Access to the osm nodes stored on disc in the process() step
     * @param wayReader Access to the osm ways stored on disc in the process() step
     * @return Linear rings with the given role
     */
    private fun relationGetRings(relation: Relation,
                                 role: String,
                                 wayReader: IndexedObjectStoreReader<WayContainer>,
                                 nodeReader: IndexedObjectStoreReader<NodeContainer>
    ) : Set<LinearRing> {
        val lines = mutableSetOf<LineString>()
        val rings = mutableSetOf<LinearRing>()

        for (member in relation.members) {
            if ((member.memberType == EntityType.Way) && (member.memberRole == role)) {
                val way = wayReader.get(member.memberId).entity
                val geom = getGeom(way, nodeReader)
                if (geom is Polygon) {
                    rings.add(geom.exteriorRing)
                } else if (geom is LineString) {
                    lines.add(geom)
                }
            }
        }

        val merger = LineMerger()
        merger.add(lines)
        @Suppress("UNCHECKED_CAST")
        val mergedLines = merger.mergedLineStrings as List<LineString>

        for (line in mergedLines) {
            if (line.isRing && line.coordinates.size > 2) {
                val ring = geometryFactory.createLinearRing(line.coordinates)
                rings.add(ring)
            }
        }
        return rings
    }

    /**
     * Determine the MapObjectTypes of the OSM object. Can be more than one, e.g., a building can also be an office.
     * @param entity OSM object
     * @return All MapObjectTypes of the object
     */
    private fun determineTypes(entity: Entity) : List<MapObjectType> {
        val rslt = mutableListOf<MapObjectType>()
        for (tag in entity.tags) {
            val type = when (tag.key) {
                "building"  -> MapObjectType.BUILDING
                "office"    -> MapObjectType.OFFICE
                "shop"      -> MapObjectType.SHOP
                "tourism"   -> MapObjectType.TOURISM
                "landuse"   -> getShortLanduseDescription(tag.value) ?: continue
                "amenity"   -> {
                    when (tag.value) {
                        "school" -> MapObjectType.SCHOOL
                        "university" -> MapObjectType.UNIVERSITY
                        "restaurant" -> MapObjectType.RESTAURANT
                        "place_of_worship" -> MapObjectType.PLACE_OF_WORSHIP
                        "cafe" -> MapObjectType.CAFE
                        "fast_food" -> MapObjectType.FAST_FOOD
                        "kindergarten" -> MapObjectType.KINDER_GARTEN
                        else -> continue
                    }
                }
                else -> continue
            }
            rslt.add(type)
        }
        return rslt
    }

    /**
     * Determine if the OSM object is relevant for OMOD
     *
     * @param entity OSM object
     * @return true -> object is relevant
     */
    private fun relevantObject(entity: Entity) : Boolean {
        return determineTypes(entity).isNotEmpty()
    }

    /**
     * Go through stored OSM data and apply a consumer function to every relevant entry.
     *
     * @param reader Access to the stored OSM data
     * @param idTracker  ID-Tracker see Osmosis documentation
     * @param foundCallback The consumer lambda function that will be applied to all relevant objects.
     */
    private fun <T> searchForRelevant(reader: IndexedObjectStoreReader<T>, idTracker: IdTracker,
                                      foundCallback: (container: T) -> Unit) {
        val iterator = idTracker.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val container = reader.get(id)
            foundCallback(container)
        }
    }

    /**
     * Called when process() is finished. Searches the stored data for relevant objects
     * and transforms then to MapObjects.
     */
    override fun complete() {
        allNodes.complete()
        allWays.complete()
        allRelations.complete()

        val nodeReader = allNodes.createReader()
        val wayReader = allWays.createReader()
        val relationReader = allRelations.createReader()

        searchForRelevant(nodeReader, relevantNodes) { container ->
            val entity = container.entity
            val types = determineTypes(entity)
            val geom = getGeom(entity)
            if (!geom.isEmpty) {
                for (type in types) {
                    mapObjects.add(MapObject(entity.id, type, geom))
                }
            }
        }

        searchForRelevant(wayReader, relevantWays) { container ->
            val entity = container.entity
            val types = determineTypes(entity)
            val geom = getGeom(entity, nodeReader)
            if (!geom.isEmpty) {
                for (type in types) {
                    mapObjects.add(MapObject(entity.id, type, geom))
                }
            }
        }

        searchForRelevant(relationReader, relevantRelations) { container ->
            val entity = container.entity
            val types = determineTypes(entity)
            val geom = getGeom(entity, wayReader, nodeReader)
            if (!geom.isEmpty) {
                for (type in types) {
                    mapObjects.add(MapObject(entity.id, type, geom))
                }
            }
        }

        nodeReader.close()
        wayReader.close()
        relationReader.close()
    }

    /**
     * Called then OSMProcessor is discarded. Relinquish disc space.
     */
    override fun close() {
        allNodes.close()
        allWays.close()
        allRelations.close()
    }
}