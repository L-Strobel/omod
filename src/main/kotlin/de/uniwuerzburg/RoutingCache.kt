package de.uniwuerzburg

import com.graphhopper.GraphHopper
import org.locationtech.jts.geom.Coordinate
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stores routing information
 */
class RoutingCache(
    private val mode: RoutingMode,
    private val hopper: GraphHopper?
) {
    private val sizeLimit = 20_000 // This value caps the memory consumption roughly at 5 GB
    private val sizeLimitSecTable = sizeLimit * 3
    private var table: HashMap<LocationOption, HashMap<LocationOption, Float>> = MaxSizeHashMap(sizeLimit)
    private val logger = LoggerFactory.getLogger(RoutingCache::class.java)

    fun load(locations: List<LocationOption>, cacheDir: Path){
        // Bounds
        val latMin = locations.minOfOrNull { it.latlonCoord.x } ?:0.0
        val lonMin = locations.minOfOrNull { it.latlonCoord.y } ?:0.0
        val latMax = locations.maxOfOrNull { it.latlonCoord.x } ?:0.0
        val lonMax = locations.maxOfOrNull { it.latlonCoord.y } ?:0.0
        // Unique cache path
        val cachePath = Paths.get(
            cacheDir.toString(),
            "routing-matrix-cache",
            "RoutingMode${mode}NCells${locations.size}" +
            "GridBounds${listOf(latMin, latMax, lonMin, lonMax).toString().replace(" ", "")}"
        )
        // Fill cache
        if (cachePath.toFile().exists()) {
            fillFromOOMCache(cachePath, locations)
        } else {
            fill(locations)
            toOOMCache(cachePath)
        }
    }

    private fun fill(locations: List<LocationOption>) {
        val modeInfo = if (mode == RoutingMode.GRAPHHOPPER) {
            "Routing mode is GRAPHHOPPER so this will take a while. If you want to run quick tests use BEELINE."
        } else {
            ""
        }
        logger.info("Calculating distance matrix. $modeInfo")

        var progress = 0
        for (origin in locations) {
            if (progress % 100 == 0) {
                logger.info("Progress: ${ "%.2f".format(null, 100.0 * progress / locations.size) } %")
            }

            val oTable  = if (!table.containsKey(origin)) {
                table[origin] = MaxSizeHashMap(sizeLimitSecTable)
                table[origin]!!
            } else {
                table[origin]!!
            }

            if (origin is DummyLocation) { continue }

            when (mode) {
                RoutingMode.BEELINE -> {
                    for (destination in locations) {
                        if (destination is DummyLocation) { continue }
                        if (oTable[destination] != null)  { continue }
                        oTable[destination] = calcDistanceBeeline(origin, destination).toFloat()
                    }
                }
                RoutingMode.GRAPHHOPPER -> {
                    val qGraph = prepareQGraph(hopper!!, locations.filterIsInstance<RealLocation>())
                    val distances = querySPT(qGraph, origin as RealLocation, locations)

                    for ((i, destination) in locations.withIndex()) {
                        if (destination is DummyLocation) { continue }
                        if (oTable[destination] != null)  { continue }

                        oTable[destination] = distances[i]?.toFloat() ?: calcDistance(origin, destination).toFloat()
                    }
                }
            }
            progress += 1
        }
    }

    fun getDistances(origin: LocationOption, destinations: List<LocationOption>) : FloatArray {
        if (origin is DummyLocation) {
            return destinations.map { calcDistanceBeeline(origin, it).toFloat() }.toFloatArray()
        }
        val oTable  = if (!table.containsKey(origin)) {
            table[origin] = MaxSizeHashMap(sizeLimitSecTable)
            table[origin]!!
        } else {
            table[origin]!!
        }
        return FloatArray(destinations.size) {
            val destination = destinations[it]
            if (destination is DummyLocation) {
                calcDistanceBeeline(origin, destination).toFloat()
            } else {
                val entry = oTable[destination]
                if (entry == null) {
                    val distance = calcDistance(origin, destination).toFloat()
                    oTable[destination] = distance
                    distance
                } else {
                    oTable[destination]!!
                }
            }
        }
    }
    private fun calcDistance(origin: LocationOption, destination: LocationOption) : Double {
        return when (mode) {
            RoutingMode.BEELINE -> calcDistanceBeeline(origin, destination)
            RoutingMode.GRAPHHOPPER -> calcDistanceGH(origin as RealLocation, destination as RealLocation, hopper!!)
        }
    }

    private fun toOOMCache (cachePath: Path) {
        Files.createDirectories(cachePath.parent)
        val fos = FileOutputStream(cachePath.toFile())
        val oos = ObjectOutputStream(fos)
        oos.writeObject(formatForCache(table))
        oos.close()
    }

    private fun fillFromOOMCache (cachePath: Path, locations: List<LocationOption>) {
        val fis = FileInputStream(cachePath.toFile())
        val ois = ObjectInputStream(fis)
        val cacheData = ois.readObject() as OOMCacheFormat

        val indicesInCache = mutableListOf<Int>()
        for (location in locations) {
            for ((i, coord) in cacheData.coords.withIndex()) {
                if ((coord.x == location.latlonCoord.x) and (coord.y == location.latlonCoord.y)) {
                    indicesInCache.add(i)
                    break
                }
            }
        }
        assert(indicesInCache.size == locations.size)

        for ((i, origin) in locations.withIndex()) {
            val oTable  = if (!table.containsKey(origin)) {
                table[origin] = MaxSizeHashMap(sizeLimitSecTable)
                table[origin]!!
            } else {
                table[origin]!!
            }

            for ((j, destination) in locations.withIndex()) {
                val originIdx = indicesInCache[i]
                val destinationIdx = indicesInCache[j]
                val value = cacheData.matrix[originIdx][destinationIdx]
                assert(value >= 0f)

                oTable[destination] = value
            }
        }
    }

    private class OOMCacheFormat  (
        val coords: Array<Coordinate>,
        val matrix: Array<FloatArray>
    ) : java.io.Serializable

    private fun formatForCache(table: HashMap<LocationOption, HashMap<LocationOption, Float>>) :
            OOMCacheFormat {
        val locations = table.keys
        val coords = table.keys.map { it.latlonCoord }.toTypedArray()
        val matrix = Array(coords.size) { FloatArray(coords.size) { -1.0f } }

        for ((i, origin) in locations.withIndex()) {
            val oTable = table[origin]!!
            for((j, destination) in locations.withIndex()) {
                val distance = oTable[destination]
                matrix[i][j] = distance ?: -1.0f
            }
        }
        return OOMCacheFormat(coords, matrix)
    }
}
