package de.uniwuerzburg.omod.routing

import com.graphhopper.GraphHopper
import com.graphhopper.util.exceptions.PointNotFoundException
import de.uniwuerzburg.omod.core.LocationOption
import de.uniwuerzburg.omod.core.ProgressBar
import de.uniwuerzburg.omod.core.RealLocation
import org.locationtech.jts.geom.Coordinate
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min


enum class RoutingMode {
    GRAPHHOPPER, BEELINE
}

/**
 * HashMap with fixed size. If the collection is full and an entry is put in the oldest entry is removed.
 * See: https://stackoverflow.com/questions/5601333/limiting-the-max-size-of-a-hashmap-in-java
 */
class MaxSizeHashMap<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>() {
    override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean {
        return size > maxSize
    }
}


/**
 * Stores routing information
 */
class RoutingCache(
    private val mode: RoutingMode,
    private val hopper: GraphHopper?,
    cacheSize: Int = 20_000
) {
    private val sizeLimit = cacheSize // This value caps the memory consumption roughly at 5 GB (I hope)
    private val prefillSize = cacheSize // Should be smaller than sizeLimit
    private val sizeLimitSecTable = cacheSize
    private var table: HashMap<LocationOption, HashMap<LocationOption, Float>> = MaxSizeHashMap(sizeLimit)
    private var cachePath: Path? = null
    private val unRoutableLocs = mutableSetOf<LocationOption>()

    fun load(locations: List<LocationOption>, cacheDir: Path, priorityValues: List<Double>?){
        // Bounds
        val latMin = locations.minOfOrNull { it.latlonCoord.x } ?:0.0
        val lonMin = locations.minOfOrNull { it.latlonCoord.y } ?:0.0
        val latMax = locations.maxOfOrNull { it.latlonCoord.x } ?:0.0
        val lonMax = locations.maxOfOrNull { it.latlonCoord.y } ?:0.0
        // Unique cache path
        cachePath = Paths.get(
            cacheDir.toString(),
            "routing-matrix-cache",
            "RoutingMode${mode}NCells${locations.size}" +
                    "GridBounds${listOf(latMin, latMax, lonMin, lonMax).toString().replace(" ", "")}"
        )
        // Fill cache
        if (cachePath!!.toFile().exists()) {
            fillFromOOMCache(cachePath!!, locations)
        } else {
            fill(locations, priorityValues!!)
            toOOMCache()
        }
    }

    private fun fill(locations: List<LocationOption>, priorityValues: List<Double>) {
        if (mode == RoutingMode.GRAPHHOPPER) {
            logger.info("Calculating distance matrix. Routing mode is GRAPHHOPPER so this will take a while. " +
                        "If you want to run quick tests use BEELINE."
            )
        } else {
            logger.info("Calculating distance matrix.")
        }

        val nLocations = min(locations.size, prefillSize)
        val highestPriorities = priorityValues.mapIndexed {i, it -> i to it}.sortedBy { it.second }.takeLast(nLocations)
        val relevantLocations = highestPriorities.map { locations[it.first] }

        var locsDone = 0
        for (origin in relevantLocations) {
            // Progressbar
            print( "Calculating distance matrix: ${ProgressBar.show(locsDone / nLocations.toDouble() )}\r" )

            if (origin !is RealLocation) { continue }

            val oTable  = if (!table.containsKey(origin)) {
                table[origin] = MaxSizeHashMap(sizeLimitSecTable)
                table[origin]!!
            } else {
                table[origin]!!
            }

            when (mode) {
                RoutingMode.BEELINE -> {
                    for (destination in relevantLocations) {
                        if (destination !is RealLocation) { continue }
                        if (oTable[destination] != null)  { continue }
                        oTable[destination] = calcDistance(origin, destination).toFloat()
                    }
                }
                RoutingMode.GRAPHHOPPER -> {
                    val qGraph = prepareQGraph(hopper!!, relevantLocations.filterIsInstance<RealLocation>())
                    val distances = querySPT(qGraph, origin, relevantLocations)

                    for ((i, destination) in relevantLocations.withIndex()) {
                        if (destination !is RealLocation) { continue }
                        if (oTable[destination] != null)  { continue }

                        val sptDistance = distances[i]?.toFloat()
                        oTable[destination] = sptDistance ?: calcDistance(origin, destination).toFloat()
                    }
                }
            }
            locsDone += 1
        }
        println("Calculating distance matrix: " + ProgressBar.done())
    }

    fun getDistances(origin: LocationOption, destinations: List<LocationOption>) : FloatArray {
        when (mode) {
            RoutingMode.BEELINE -> return destinations.map { calcDistance(origin, it).toFloat() }.toFloatArray()
            RoutingMode.GRAPHHOPPER -> {
                if (origin !is RealLocation) {
                    return destinations.map { calcDistance(origin, it).toFloat() }.toFloatArray()
                }
                val oTable  = if (!table.containsKey(origin)) {
                    table[origin] = MaxSizeHashMap(sizeLimitSecTable)
                    table[origin]!!
                } else {
                    table[origin]!!
                }
                return FloatArray(destinations.size) {
                    val destination = destinations[it]
                    if (destination !is RealLocation) {
                        calcDistance(origin, destination).toFloat()
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
        }
    }

    private fun calcDistance(origin: LocationOption, destination: LocationOption) : Double {
        if (origin == destination) {
            return origin.avgDistanceToSelf // 0.0 for Buildings
        }
        when (mode) {
            RoutingMode.BEELINE -> return calcDistanceBeeline(origin, destination)
            RoutingMode.GRAPHHOPPER -> {
                // Check if possible
                if (origin in unRoutableLocs) { return calcDistanceBeeline(origin, destination) }
                if (destination in unRoutableLocs) { return calcDistanceBeeline(origin, destination) }

                val rsp = calcDistanceGH(origin as RealLocation, destination as RealLocation, hopper!!)
                if (rsp.hasErrors()) {
                    logger.warn(
                        "Could not route from ${origin.latlonCoord} to ${destination.latlonCoord}. Fall back to Beeline."
                    )

                    for (error in rsp.errors) {
                        if (error is PointNotFoundException) {
                            if (error.pointIndex == 0) {
                                unRoutableLocs.add(origin)
                                logger.warn(
                                    "Because Point ${origin.latlonCoord} is unreachable."
                                )
                            } else {
                                unRoutableLocs.add(destination)
                                logger.warn(
                                    "Because Point ${destination.latlonCoord} is unreachable."
                                )
                            }
                        }
                    }

                    return calcDistanceBeeline(origin, destination)
                } else {
                    return rsp.best.distance
                }
            }
        }
    }

    fun toOOMCache () {
        if (cachePath != null) {
            Files.createDirectories(cachePath!!.parent)
            val fos = FileOutputStream(cachePath!!.toFile())
            val oos = ObjectOutputStream(fos)
            oos.writeObject(formatForCache(table))
            oos.close()
        } else {
            if (mode != RoutingMode.BEELINE) {
                logger.warn("Couldn't save routed distances because routing cache has not store path.")
            }
        }
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
