package de.uniwuerzburg.omod.io.overture

import com.graphhopper.util.Helper.round
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeatureCollectionNoProperties
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeaturePlaces
import de.uniwuerzburg.omod.io.geojson.GeoJsonLandUse
import de.uniwuerzburg.omod.io.geojson.GeoJsonPlaces
import de.uniwuerzburg.omod.io.json.readJson
import de.uniwuerzburg.omod.io.logger
import de.uniwuerzburg.omod.io.osm.BuildingData
import de.uniwuerzburg.omod.io.osm.MapObject
import de.uniwuerzburg.omod.io.osm.MapObjectType
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import de.uniwuerzburg.omod.utils.CRSTransformer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.DriverManager
import de.uniwuerzburg.omod.io.osm.getShortLanduseDescription
import de.uniwuerzburg.omod.io.osm.addBuildingInformation
import de.uniwuerzburg.omod.io.osm.inFocusArea

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

fun downloadOvertureLayer(fullArea: Geometry, type: String,nWorker:Int?) {

    var theme = typeThemeMap.getOrDefault(type, type)
    //Select String based on type
    val selectString = when (type) {
        "building" -> "geometry"
        "place" -> "geometry, confidence, CAST(categories AS JSON) as categories"
        "land_use" -> "geometry, class"
        else -> ""
    }
    val env = fullArea.envelopeInternal
    val xmin: Double = round(env.minY,2)
    val xmax: Double = round(env.maxY,2)
    val ymin: Double = round(env.minX,2)
    val ymax: Double = round(env.maxX,2)
    var threads=nWorker ?: 1
    // Connect to DuckDB (in-memory unless you pass a file path)
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    val stmt = conn.createStatement()

    // Load required extensions
    stmt.execute("INSTALL httpfs; LOAD httpfs;")
    stmt.execute("INSTALL spatial; LOAD spatial;")
    // Set the region for S3 access
    stmt.execute("SET s3_region='us-west-2';")
    stmt.execute("PRAGMA threads=${threads};")  // Use 4 threads (adjust based on your CPU)
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

fun readOverture(focusArea: Geometry,fullArea: Geometry,geometryFactory:GeometryFactory,transformer: CRSTransformer,nWorker: Int?): List<BuildingData> {
    logger.info("Start reading OvertureMap-File... (If this is too slow use smaller buffer size)")
    val env: Envelope = fullArea.envelopeInternal
    val xmin: Double = round(env.minY,2)
    val xmax: Double = round(env.maxY,2)
    val ymin: Double = round(env.minX,2)
    val ymax: Double = round(env.maxX,2)
    //Check if it already exists, then download
    for (type in types) {
        downloadOvertureLayer(fullArea, type, nWorker)
    }

    val geoBuildings:GeoJsonFeatureCollectionNoProperties = readJson(File("omod_cache//overture//building${xmin}_${xmax}_${ymin}_${ymax}.geojson"))
    val geoPlaces: GeoJsonPlaces = readJson(File("omod_cache//overture//place${xmin}_${xmax}_${ymin}_${ymax}.geojson"))
    val geoLandUse: GeoJsonLandUse= readJson(File("omod_cache//overture//land_use${xmin}_${xmax}_${ymin}_${ymax}.geojson"))
    geoPlaces.features=geoPlaces.features.filter { it.properties.confidence >0.5 }
    val buildings = geoBuildings.features
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

    addBuildingInformation(buildings,extraInfoTree)

    inFocusArea(buildings,focusArea,geometryFactory,transformer)

    return buildings.toList()
}