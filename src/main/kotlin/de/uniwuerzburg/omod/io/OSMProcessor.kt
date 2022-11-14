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

// Enumeration of all map objects used in OMOD
enum class MapObjectType {
    BUILDING, OFFICE, SHOP, SCHOOL, UNIVERSITY,
    LU_RESIDENTIAL, LU_COMMERCIAL, LU_INDUSTRIAL
}

class MapObject (
    val id: Long,
    val type: MapObjectType,
    val geometry: Geometry
)

// Map landuse keys to the that used in OMOD
private fun getShortLanduseDescription(osmDescription: String): MapObjectType? {
    return when(osmDescription) {
        "residential"       -> MapObjectType.LU_RESIDENTIAL
        "commercial"        -> MapObjectType.LU_COMMERCIAL
        "retail"            -> MapObjectType.LU_COMMERCIAL
        "industrial"        -> MapObjectType.LU_INDUSTRIAL
        "military"          -> MapObjectType.LU_INDUSTRIAL
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
         */
        else -> null
    }
}

/**
 *Find all the entities with the right tags and keys in the area.
 * @property mapObjects The result. Access this after running the pipeline. Resulting Geometries are in Mercator projection.
 */
class OSMProcessor(idTrackerType: IdTrackerType,
                   private val geometryFactory: GeometryFactory
) : EntityProcessor, Sink {
    val mapObjects = mutableListOf<MapObject>()

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

    override fun process(entityContainer: EntityContainer?) {
        entityContainer?.process(this)
    }

    override fun process(nodeContainer: NodeContainer) {
        val node = nodeContainer.entity

        // Remember for way and relation parsing
        allNodes.add(node.id, nodeContainer)

        // Is entity a relevant map object?
        if (relevantObject(node)) {
            relevantNodes.set(node.id)
        }
    }

    override fun process(wayContainer: WayContainer) {
        val way = wayContainer.entity

        // Remember for way and relation parsing
        allWays.add(way.id, wayContainer)

        // Is entity a relevant map object?
        if (relevantObject(way)) {
            relevantWays.set(way.id)
        }
    }

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

    private fun getGeom(node: Node) : Point {
        val coords = Coordinate(node.latitude, node.longitude)
        val geometry = geometryFactory.createPoint(coords)

        if (!geometry.isValid) {
            throw Error("Faulty geometry found: ${geometry.toText()}")
        }

        return geometry
    }

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

    private fun relationGetRings(relation: Relation, role: String,
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

    private fun determineTypes(entity: Entity) : List<MapObjectType> {
        val rslt = mutableListOf<MapObjectType>()
        for (tag in entity.tags) {
            val type = when (tag.key) {
                "building"  -> MapObjectType.BUILDING
                "office"    -> MapObjectType.OFFICE
                "shop"      -> MapObjectType.SHOP
                "landuse"   -> getShortLanduseDescription(tag.value) ?: continue
                "amenity"   -> {
                    if (tag.value == "school") {
                        MapObjectType.SCHOOL
                    } else if (tag.value == "university") {
                        MapObjectType.UNIVERSITY
                    } else {
                        continue
                    }
                }
                else -> continue
            }
            rslt.add(type)
        }
        return rslt
    }

    private fun relevantObject(entity: Entity) : Boolean {
        return determineTypes(entity).isNotEmpty()
    }

    private fun <T> searchForRelevant(reader: IndexedObjectStoreReader<T>, idTracker: IdTracker,
                                      foundCallback: (container: T) -> Unit) {
        val iterator = idTracker.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val container = reader.get(id)
            foundCallback(container)
        }
    }

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

    override fun close() {
        allNodes.close()
        allWays.close()
        allRelations.close()
    }
}