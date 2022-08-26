package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import org.locationtech.jts.io.WKBReader
import org.locationtech.jts.io.WKTWriter
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

data class OSMBuilding(
    val osm_id: Int,
    val geometry: Geometry,
) {
    val area = geometry.area
    var landuse: String = "NONE"
    var nShops: Double = 0.0
    var nOffices: Double = 0.0
    var nSchools: Double = 0.0
    var nUnis: Double = 0.0
    var inFocusArea: Boolean = false
    var population: Double? = null
    var regionType: Int = 0
}

data class OSMLanduse(
    val type: String,
    val geometry: Geometry
)

enum class POIType {
    SHOP, OFFICE, SCHOOL, UNIVERSITY
}

data class OSMpoi (
    val type: POIType,
    val geometry: Geometry
)

private fun shortLanduseDescription(osmDescription: String): String {
    return when(osmDescription) {
        "residential" -> "RESIDENTIAL"
        "commercial" -> "COMMERCIAL"
        "retail" -> "COMMERCIAL"
        "industrial" -> "INDUSTRIAL"
        "military" -> "INDUSTRIAL"
        "cemetery" -> "RECREATIONAL"
        "meadow" -> "RECREATIONAL"
        "grass" -> "RECREATIONAL"
        "park" -> "RECREATIONAL"
        "recreation_ground" -> "RECREATIONAL"
        "allotments" -> "RECREATIONAL"
        "scrub" -> "RECREATIONAL"
        "heath" -> "RECREATIONAL"
        "farmland" -> "AGRICULTURE"
        "farmyard" -> "AGRICULTURE"
        "orchard" -> "AGRICULTURE"
        "forest" -> "FOREST"
        "quarry" -> "FOREST"
        else -> "NONE"
    }
}

private fun <T> executeQuery(query: String, stmt: Statement, itemMaker: (ResultSet) -> T): List<T> {
    val rslt = mutableListOf<T>()
    val queryRslt = stmt.executeQuery( query )
    while (queryRslt.next()) {
        rslt.add(itemMaker(queryRslt))
    }
    return rslt
}

private fun executePOIQuery(queryAND:String, poiType: POIType, areaWKT: String, areaSRID: Int,
                            stmt: Statement, geometryFactory: GeometryFactory): List<OSMpoi> {
    val pois = mutableListOf<OSMpoi>()

    // Polygons
    val queryPolys =
        """
            SELECT ST_AsBinary(way) as way
            FROM planet_osm_polygon
            WHERE ST_Intersects(way, ST_GeomFromText('${areaWKT}', ${areaSRID}))
            $queryAND
        """
    pois.addAll(executeQuery(queryPolys, stmt) {
        OSMpoi (
            poiType,
            WKBReader(geometryFactory).read(it.getBytes("way"))
        )
    })

    // Points
    val queryPoints =
        """
            SELECT ST_AsBinary(way) as way
            FROM planet_osm_point
            WHERE ST_Intersects(way, ST_GeomFromText('${areaWKT}', ${areaSRID}))
            $queryAND
        """
    pois.addAll(executeQuery(queryPoints, stmt) {
        OSMpoi (
            poiType,
            WKBReader(geometryFactory).read(it.getBytes("way"))
        )
    })

    return pois
}

fun readOSMDataFromPG (db_url: String, db_user: String, db_password: String,
                       area_osm_ids: List<Int>, buffer_radius: Double): List<OSMBuilding> {
    val geometryFactory = GeometryFactory()

    // Connect to postgres database. Needs postgis plugin.
    val connection = DriverManager.getConnection(db_url, db_user, db_password)
    connection.autoCommit = false

    val stmt = connection.createStatement()

    // Add negative signed version of osm id. Necessary because osm2pgsql weirdness.
    val osmIdsPm = mutableListOf<Int>()
    for (id in area_osm_ids) {
        osmIdsPm.add(id)
        osmIdsPm.add(id * -1)
    }

    // Get focus area
    val focusAreaQuery =
        """
            SELECT ST_AsEWKB(way) as way
            FROM planet_osm_polygon
            WHERE osm_id in (${osmIdsPm.joinToString(separator = ",")})
        """
    val areaLst = executeQuery(focusAreaQuery, stmt) { WKBReader(geometryFactory).read( it.getBytes("way")) }
    val areaSRID = areaLst.first().srid
    val focusArea = GeometryCollection(areaLst.toTypedArray(), geometryFactory)
    val fullAreaWKT = WKTWriter().write(
        focusArea.buffer(buffer_radius).convexHull()
    )

    // Get buildings
    val buildingsQuery =
        """
            SELECT osm_id, ST_AsBinary(way) as way
            FROM planet_osm_polygon
            WHERE ST_Intersects(way, ST_GeomFromText('${fullAreaWKT}', ${areaSRID}))
            AND building IS NOT NULL
        """
    val buildings = executeQuery(buildingsQuery, stmt) {
        OSMBuilding(
            it.getInt("osm_id"),
            WKBReader(geometryFactory).read( it.getBytes("way"))
        )
    }

    // Get Landuse
    val landuseQuery =
        """
            SELECT landuse, ST_AsBinary(way) as way
            FROM planet_osm_polygon
            WHERE ST_Intersects(way, ST_GeomFromText('${fullAreaWKT}', ${areaSRID}))
            AND landuse IS NOT NULL
        """
    val landuses = executeQuery(landuseQuery, stmt) {
        OSMLanduse(
            it.getString("landuse"),
            WKBReader(geometryFactory).read( it.getBytes("way"))
        )
    }

    // Get POIs
    val shops = executePOIQuery("AND shop IS NOT null", POIType.SHOP, fullAreaWKT,
        areaSRID, stmt, geometryFactory)
    val offices = executePOIQuery("AND office IS NOT NULL", POIType.OFFICE, fullAreaWKT,
        areaSRID, stmt, geometryFactory)
    val schools = executePOIQuery("AND amenity = 'school'", POIType.SCHOOL, fullAreaWKT,
        areaSRID, stmt, geometryFactory)
    val universities = executePOIQuery("AND amenity = 'university'", POIType.UNIVERSITY, fullAreaWKT,
        areaSRID, stmt, geometryFactory)
    val pois = shops + offices + schools + universities

    // Create spatial indices
    val landuseTree = HPRtree()
    val poisTree = HPRtree()
    for (landuse in landuses) {
        landuseTree.insert(landuse.geometry.envelopeInternal, landuse)
    }
    for (poi in pois) {
        poisTree.insert(poi.geometry.envelopeInternal, poi)
    }

    // Add information to buildings
    for (building in buildings) {
        // Landuse
        val landuseOptions = landuseTree.query(building.geometry.envelopeInternal)
            .map { it as OSMLanduse }
            .filter { it.geometry.intersects(building.geometry) }
        if (landuseOptions.isNotEmpty()) {
            building.landuse = shortLanduseDescription(landuseOptions.first().type)
        }

        // POIs
        val poiOptions = poisTree.query(building.geometry.envelopeInternal)
            .map { it as OSMpoi }
            .filter { it.geometry.intersects(building.geometry) }
        for (poi in poiOptions) {
            when(poi.type) {
                POIType.SHOP -> building.nShops +=1
                POIType.OFFICE -> building.nOffices +=1
                POIType.SCHOOL -> building.nSchools +=1
                POIType.UNIVERSITY -> building.nUnis +=1
            }
        }

        // Focus area
        building.inFocusArea = building.geometry.intersects(focusArea)
    }
    connection.close()
    return buildings
}

fun createModelArea(dbUrl: String, dbUser: String, dbPassword: String,
                    areaOsmIds: List<Int>, bufferRadius: Double = 0.0,
                    censusFile: File? = null, regionTypeFile: File? = null): GeoJsonFeatureCollection {
    val geometryFactory = GeometryFactory()

    val osmBuildings = readOSMDataFromPG(dbUrl, dbUser, dbPassword, areaOsmIds, bufferRadius)

    // Spatial index
    val buildingsTree = HPRtree()
    for (building in osmBuildings) {
        buildingsTree.insert(building.geometry.envelopeInternal, building)
    }

    // Add census data if available
    if (censusFile != null) {
        val censusData: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
            .decodeFromString(censusFile.readText(Charsets.UTF_8))

        for (censusEntree in censusData.features) {
            val population = (censusEntree.properties as GeoJsonCensusProperties).population
            val censusZone = censusEntree.geometry.toJTS(geometryFactory)

            val intersectingBuildings = buildingsTree.query(censusZone.envelopeInternal)
                .map { it as OSMBuilding }
                .filter { it.geometry.intersects(censusZone) }

            for (building in intersectingBuildings) {
                building.population = population / intersectingBuildings.count().toDouble()
            }
        }
    }

    // Add region type data if available
    if (regionTypeFile != null) {
        val regionTypeData: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
            .decodeFromString(regionTypeFile.readText(Charsets.UTF_8))

        for (regionTypeEntree in regionTypeData.features) {
            val regionType = (regionTypeEntree.properties as GeoJsonRTProperties).region_type
            val regionGeom = regionTypeEntree.geometry.toJTS(geometryFactory)

            val intersectingBuildings = buildingsTree.query(regionGeom.envelopeInternal)
                .map { it as OSMBuilding }
                .filter { it.geometry.intersects(regionGeom) }

            for (building in intersectingBuildings) {
                building.regionType = regionType
            }
        }
    }
    val outBuildings = GeoJsonFeatureCollection(
        osmBuildings.map {
            val center = it.geometry.centroid
            val coords = mercatorToLatLon(center.x, center.y)
            val geometry = GeoJsonPoint(listOf(coords.y, coords.x))

            val properties = GeoJsonBuildingProperties(
                osm_id = it.osm_id,
                region_type = it.regionType,
                in_focus_area = it.inFocusArea,
                area = it.area,
                population = it.population,
                landuse = it.landuse,
                number_shops = it.nShops,
                number_offices = it.nOffices,
                number_schools = it.nSchools,
                number_universities = it.nUnis,
            )
            GeoJsonFeature(geometry, properties)
        }
    )
    return outBuildings
}