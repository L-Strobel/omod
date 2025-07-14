package de.uniwuerzburg.omod.io.overture

import com.graphhopper.util.Helper.round
import de.uniwuerzburg.omod.core.models.Landuse
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeatureCollectionNoProperties
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeaturePlaces
import de.uniwuerzburg.omod.io.geojson.GeoJsonLandUse
import de.uniwuerzburg.omod.io.geojson.GeoJsonPlaces
import de.uniwuerzburg.omod.io.json.readJson
import de.uniwuerzburg.omod.io.logger
import de.uniwuerzburg.omod.io.osm.BuildingData
import de.uniwuerzburg.omod.utils.fastCovers
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import de.uniwuerzburg.omod.utils.CRSTransformer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.DriverManager


/**
* Determine the MapObjectTypes of the OSM object. Can be more than one, e.g., a building can also be an office.
* @param entity OSM object
* @return All MapObjectTypes of the object
*/
private fun determineTypes(entity: GeoJsonFeaturePlaces,tagsDict:Map<String, Map<String, String>>) : List<MapObjectType> {
    val rslt = mutableListOf<MapObjectType>()
    val primary =tagsDict[entity.properties.categories.primary]
    val keys = primary?.keys ?: emptySet<String>()
    for (tag in keys) {
        val type = when (tag) {
            "building"  -> MapObjectType.BUILDING
            "office"    -> MapObjectType.OFFICE
            "shop"      -> MapObjectType.SHOP
            "tourism"   -> MapObjectType.TOURISM
            "landuse"   -> getShortLanduseDescription(primary?.get(tag).toString()) ?: continue
            "amenity"   -> {
                when (primary?.get(tag).toString()) {
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

val typeThemeMap = mapOf(
    "address" to "addresses",
    "bathymetry" to "base",
    "building" to "buildings",
    "building_part" to "buildings",
    "division" to "divisions",
    "division_area" to "divisions",
    "division_boundary" to "divisions",
    "place" to "places",
    "segment" to "transportation",
    "connector" to "transportation",
    "infrastructure" to "base",
    "land" to "base",
    "land_cover" to "base",
    "land_use" to "base",
    "water" to "base"
)

fun downloadOvertureLayer(fullArea: Geometry, type: String) {

    var theme = typeThemeMap.getOrDefault(type, type)
    //Select String based on type
    val selectString = when (type) {
        "building" -> "geometry"
        "place" -> "geometry, confidence, CAST(categories AS JSON) as categories"
        "land_use" -> "geometry, class"
        else -> ""
    }
    val env: Envelope = fullArea.getEnvelopeInternal()
    val xmin: Double = round(env.getMinY(),2)
    val xmax: Double = round(env.getMaxY(),2)
    val ymin: Double = round(env.getMinX(),2)
    val ymax: Double = round(env.getMaxX(),2)

    // Connect to DuckDB (in-memory unless you pass a file path)
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    val stmt = conn.createStatement()

    // Load required extensions
    stmt.execute("INSTALL httpfs; LOAD httpfs;")
    stmt.execute("INSTALL spatial; LOAD spatial;")
    // Set the region for S3 access
    stmt.execute("SET s3_region='us-west-2';")
    stmt.execute("PRAGMA threads=6;")  // Use 4 threads (adjust based on your CPU)
    // Perform the COPY query to download filtered places and save to GeoJSON
    val queryString = """
    COPY (
        SELECT   
            $selectString
        FROM read_parquet(
            's3://overturemaps-us-west-2/release/2025-05-21.0/theme=$theme/type=$type/*',
            filename = true,
            hive_partitioning = 1
        )
        WHERE bbox.xmin BETWEEN ${xmin} AND ${xmax}
          AND bbox.ymin BETWEEN ${ymin} AND ${ymax}
    )
    TO 'omod_cache//overture//$type${xmin}_${xmax}_${ymin}_${ymax}.geojson'
    WITH (FORMAT GDAL, DRIVER 'GeoJSON');
""".trimIndent()

    stmt.execute(queryString)

    stmt.close()
    conn.close()
}

val types = listOf("place","land_use", "building")

fun loadTagsDict(path: String): Map<String, Map<String, String>> {
    val jsonString = File(path).readText()
    val json = Json { ignoreUnknownKeys = true }
    return json.decodeFromString(jsonString)
}

fun readOverture(focusArea: Geometry,fullArea: Geometry,geometryFactory:GeometryFactory,transformer: CRSTransformer): List<BuildingData> {
    logger.info("Start reading OvertureMap-File... (If this is too slow use smaller BoundingBox)")
    val env: Envelope = fullArea.getEnvelopeInternal()
    val xmin: Double = round(env.getMinY(),2)
    val xmax: Double = round(env.getMaxY(),2)
    val ymin: Double = round(env.getMinX(),2)
    val ymax: Double = round(env.getMaxX(),2)
    //Check if it already exists, then download
    for (type in types) {
        if (!File("omod_cache//overture//$type${xmin}_${xmax}_${ymin}_${ymax}.geojson").exists()) {
            downloadOvertureLayer(fullArea, type)
        }
        else{
            logger.info("OvertureMaps Data already in cache")
        }

    }

    val geometryFactory = GeometryFactory()

    val geoBuildings:GeoJsonFeatureCollectionNoProperties = readJson(File("omod_cache//overture//building${xmin}_${xmax}_${ymin}_${ymax}.geojson"))
    val geoPlaces: GeoJsonPlaces = readJson(File("omod_cache//overture//place${xmin}_${xmax}_${ymin}_${ymax}.geojson"))
    val geoLandUse: GeoJsonLandUse= readJson(File("omod_cache//overture//land_use${xmin}_${xmax}_${ymin}_${ymax}.geojson"))
    geoPlaces.features=geoPlaces.features.filter { it.properties.confidence >0.5 }
    val buildings = geoBuildings.features
        .asReversed()
        .mapIndexedNotNull { index, feature ->
            val geom = transformer.toModelCRS(feature.geometry.toJTS(factory = geometryFactory))
            if (geom.area > 10) BuildingData(index.toLong(), geom) else null
        }
        .toMutableList()
    val extraInfoTree = HPRtree()

    val tagsDict = loadTagsDict("C:\\Daten\\Forschung\\Sustainable Work Culture\\Code\\MapDataEnhancement\\tags.json")
    var idCounter = 0L

    geoPlaces.features.forEach { point ->
        val types = determineTypes(point, tagsDict)
        if (types.isNotEmpty()) {
            val geom = transformer.toModelCRS(point.geometry.toJTS(factory = geometryFactory))
            types.forEach { type ->
                val extraInfo = MapObject(idCounter++, type, geom)
                extraInfoTree.insert(geom.envelopeInternal, extraInfo)
            }
        }
    }

    geoLandUse.features.forEach { point ->
        val type=getShortLanduseDescription(point.properties.landUseClass)
        val geom = transformer.toModelCRS(point.geometry.toJTS(geometryFactory))
        if (type != null) {
            val extraInfo = MapObject(idCounter++, type, geom)
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
                MapObjectType.PLACE_OF_WORSHIP  -> building.nPlaceOfWorship += 1
                MapObjectType.CAFE              -> building.nCafe += 1
                MapObjectType.FAST_FOOD         -> building.nFastFood += 1
                MapObjectType.KINDER_GARTEN     -> building.nKinderGarten += 1
                MapObjectType.TOURISM           -> building.nTourism += 1
                MapObjectType.LU_RESIDENTIAL    -> building.landuse = Landuse.RESIDENTIAL
                MapObjectType.LU_COMMERCIAL     -> building.landuse = Landuse.COMMERCIAL
                MapObjectType.LU_RETAIL         -> building.landuse = Landuse.RETAIL
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

    return buildings.toList()
}