package de.uniwuerzburg.omod.io.overture

import com.graphhopper.util.Helper.round
import de.uniwuerzburg.omod.io.geojson.*
import de.uniwuerzburg.omod.io.geojson.property.OverturePlaceProperties
import de.uniwuerzburg.omod.io.geojson.property.OvertureLandUseProperties
import de.uniwuerzburg.omod.io.inFocusArea
import de.uniwuerzburg.omod.io.json.readJson
import de.uniwuerzburg.omod.io.json.readJsonFromResource
import de.uniwuerzburg.omod.io.logger
import de.uniwuerzburg.omod.io.osm.*
import de.uniwuerzburg.omod.utils.CRSTransformer
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.hprtree.HPRtree
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager

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
 * Determine the MapObjectTypes of the Overture object. Can be more than one, e.g., a building can also be an office.
 * @return All MapObjectTypes of the object
 */
private fun determineTypes(
    entity: GeoJsonFeature<OverturePlaceProperties>, tagsDict:Map<String, Map<String, String>>
) : List<MapObjectType> {
    val rslt = mutableListOf<MapObjectType>()
    val primary = tagsDict[entity.properties.categories.primary]
    val keys = primary?.keys ?: emptySet<String>()

    for (key in keys) {
        val value = primary?.get(key).toString()
        val type = determineType(key, value)
        if (type != null) {
            rslt.add(type)
        }
    }
    return rslt
}

fun downloadOvertureLayer(fullArea: Geometry, type: String, overtureTmpDir: Path, nWorker:Int? = null) {
    val theme = typeThemeMap.getOrDefault(type, type)

    //Select String based on type
    val selectString = when (type) {
        "building" -> "geometry"
        "place" -> "geometry, confidence, CAST(categories AS JSON) as categories"
        "land_use" -> "geometry, class"
        else -> ""
    }
    val env = fullArea.envelopeInternal
    val xmin: Double = round(env.minY, 2)
    val xmax: Double = round(env.maxY, 2)
    val ymin: Double = round(env.minX, 2)
    val ymax: Double = round(env.maxX, 2)
    var threads = nWorker ?: 1

    // Connect to DuckDB (in-memory unless you pass a file path)
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    val stmt = conn.createStatement()

    // Load required extensions
    stmt.execute("INSTALL httpfs; LOAD httpfs;")
    stmt.execute("INSTALL spatial; LOAD spatial;")

    // Set the region for S3 access
    stmt.execute("SET s3_region='us-west-2';")
    stmt.execute("PRAGMA threads=${threads};")

    // Perform the COPY query to download filtered places and save to GeoJSON
    val cacheLocation = Paths.get(overtureTmpDir.toString(),
        "$type${xmin}_${xmax}_${ymin}_${ymax}.geojson"
    ).toString().replace("\\", "/")
    val queryString = """
    COPY (
        SELECT   
            $selectString
        FROM read_parquet(
            's3://overturemaps-us-west-2/release/2025-05-21.0/theme=$theme/type=$type/*',
            filename = true,
            hive_partitioning = 1
        )
        WHERE bbox.xmin BETWEEN $xmin AND $xmax
          AND bbox.ymin BETWEEN $ymin AND $ymax
    )
    TO '$cacheLocation'
    WITH (FORMAT GDAL, DRIVER 'GeoJSON');
    """.trimIndent()

    stmt.execute(queryString)

    stmt.close()
    conn.close()
}

fun readOverture(
    focusArea: Geometry, fullArea: Geometry, geometryFactory:GeometryFactory, transformer: CRSTransformer,
    nWorker: Int?, cacheDir: Path
): List<BuildingData> {


    val overtureTmpDir = Paths.get(cacheDir.toString(),
        "overture/"
    )
    Files.createDirectories(overtureTmpDir)

    val env: Envelope = fullArea.envelopeInternal
    val xmin: Double = round(env.minY,2)
    val xmax: Double = round(env.maxY,2)
    val ymin: Double = round(env.minX,2)
    val ymax: Double = round(env.maxX,2)

    // Download
    logger.info("Downloading OvertureMap-File... (If this is too slow use smaller buffer size)")
    for (type in listOf("place", "land_use", "building")) {
        downloadOvertureLayer(fullArea, type, overtureTmpDir, nWorker)
    }
    logger.info("OvertureMap-File downloaded!")

    logger.info("Start reading OvertureMap-File...")
    // Read downloaded files
    val geoBuildings:GeoJsonFeatureCollectionNoProperties = readJson(
        Paths.get(overtureTmpDir.toString(),
            "building${xmin}_${xmax}_${ymin}_${ymax}.geojson"
        )
    )
    val geoPlaces: GeoJsonFeatureCollection<OverturePlaceProperties> = readJson(
        Paths.get(overtureTmpDir.toString(),
            "place${xmin}_${xmax}_${ymin}_${ymax}.geojson"
        )
    )
    val geoLandUse: GeoJsonFeatureCollection<OvertureLandUseProperties> = readJson(
        Paths.get(overtureTmpDir.toString(),
            "land_use${xmin}_${xmax}_${ymin}_${ymax}.geojson"
        )
    )

    // Get buildings
    val buildings = geoBuildings.features
        .mapIndexedNotNull { index, feature ->
            val geom = transformer.toModelCRS(feature.geometry.toJTS(factory = geometryFactory))
            if (geom.area > 10) BuildingData(index.toLong(), geom) else null
        }
        .toMutableList()

    // POIs and Landuse
    val tagsDict: Map<String, Map<String, String>> = readJsonFromResource("tags.json")
    var idCounter = 0L
    val extraInfoTree = HPRtree()

    val placesFeatures = geoPlaces.features.filter { it.properties.confidence >0.5 }
    placesFeatures.forEach { point ->
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
        building.addInformation(extraInfos)
    }

    // Is building in focus area?
    inFocusArea(buildings,focusArea,geometryFactory,transformer)

    // Delete temporary files
    overtureTmpDir.toFile().deleteRecursively()

    logger.info("Overture data read!")
    return buildings
}