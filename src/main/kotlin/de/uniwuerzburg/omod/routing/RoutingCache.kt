package de.uniwuerzburg.omod.routing

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.graphhopper.GraphHopper
import com.graphhopper.util.exceptions.PointNotFoundException
import de.uniwuerzburg.omod.core.models.LocationOption
import de.uniwuerzburg.omod.utils.ProgressBar
import de.uniwuerzburg.omod.core.models.RealLocation
import kotlinx.coroutines.*
import org.locationtech.jts.geom.Coordinate
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Distance calculation methods
 */
enum class RoutingMode {
    GRAPHHOPPER, BEELINE
}

/**
 * Combination of two locations with no ordering.
 * Used as key for the routing cache. The assumption is that the Distance A->B equals the distance B->A
 *
 * @param a location option A
 * @param b location option B
 */
private class UnorderedODPair(a: LocationOption, b: LocationOption) {
    val first = a
    val second = b

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UnorderedODPair
        if ((first == other.first) and (second == other.second)) return true
        if ((first == other.second) and (second == other.first)) return true
        return false
    }

    override fun hashCode(): Int {
        return 7919 * first.hashCode() + second.hashCode()
    }
}

/**
 * Stores distance information between locations
 * @param mode Method of distance calculation used for entries
 * @param hopper GraphHopper object. Only needed if mode = RoutingMode.GRAPHHOPPER
 * @param cacheSize Maximum number origins and destinations per origin to store
 */
class RoutingCache(
    private val mode: RoutingMode,
    private val hopper: GraphHopper?,
    cacheSize: Long = 400e6.toLong(), // Maximum number of entries in the cache
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val nPrefillOrigins = sqrt(cacheSize.toFloat()).toInt() // Should be smaller than sizeLimit
    private var cache : LoadingCache<UnorderedODPair, Float> = CacheBuilder.newBuilder()
        .maximumSize(cacheSize)
        .build(
            object : CacheLoader<UnorderedODPair, Float>() {
                override fun load(key: UnorderedODPair) : Float {
                    return calcDistance(key.first, key.second).toFloat()
                }
            }
        )
    private var cachePath: Path? = null
    private val unRoutableLocs = ConcurrentHashMap.newKeySet<LocationOption>()

    /**
     * Fill cache either by loading it from a file or filling it with fill().
     * The cache stores the routes from the first n locations to the first n locations.
     * Where n is the cacheSize. The order of the locations is determined by priorityValues.
     *
     * @param locations Locations options
     * @param cacheDir The directory where the cache is stored
     * @param priorityValues Priority value that determines what locations will be cached
     */
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

    /**
     * Fill by calculating the distance between the first n locations to the first n locations.
     * Where n is the cacheSize. The order of the locations is determined by priorityValues.
     *
     * @param locations Locations options
     * @param priorityValues Priority value that determines what locations will be cached
     */
    private fun fill(locations: List<LocationOption>, priorityValues: List<Double>) {
        if (mode == RoutingMode.GRAPHHOPPER) {
            logger.info("Calculating distance matrix. Routing mode is GRAPHHOPPER so this will take a while. " +
                        "If you want to run quick tests use BEELINE."
            )
        } else {
            logger.info("Calculating distance matrix.")
        }

        val nLocations = min(locations.size, nPrefillOrigins)
        val highestPriorities = priorityValues.mapIndexed {i, it -> i to it}.sortedBy { it.second }.takeLast(nLocations)
        val relevantLocations = highestPriorities.map { locations[it.first] }

        runBlocking(dispatcher) {
            val locsDone = AtomicInteger()
            for (origin in relevantLocations) {
                if (origin !is RealLocation) {
                    continue
                }
                launch {
                    when (mode) {
                        RoutingMode.BEELINE -> {
                            for (destination in relevantLocations) {
                                if (destination !is RealLocation) {
                                    continue
                                }
                                val od = UnorderedODPair(origin, destination)
                                cache.get(od) // Put into cache if not already present
                            }
                        }

                        RoutingMode.GRAPHHOPPER -> {
                            val qGraph = prepareQGraph(hopper!!, relevantLocations.filterIsInstance<RealLocation>())
                            val sptResults = querySPT(qGraph, origin, relevantLocations)

                            for ((j, destination) in relevantLocations.withIndex()) {
                                if (destination !is RealLocation) {
                                    continue
                                }
                                val od = UnorderedODPair(origin, destination)

                                val sptDistance = sptResults[j]?.distance?.toFloat()
                                if (sptDistance != null) {
                                    cache.put(od, sptDistance)
                                } else {
                                    cache.get(od)
                                }
                            }
                        }
                    }

                    // Progressbar
                    val done = locsDone.incrementAndGet()
                    print("Calculating distance matrix: ${ProgressBar.show(done / nLocations.toDouble())}\r")
                }

            }
        }
        println("Calculating distance matrix: " + ProgressBar.done())
    }

    /**
     * Get the distances from an origin to multiple destinations.
     * Function first checks if the information is in the cache, otherwise the distance is computed.
     *
     * @param origin Origin
     * @param destinations All destinations for which the distance should be determined
     * @return Array of distances
     */
    fun getDistances(origin: LocationOption, destinations: List<LocationOption>) : FloatArray {
        when (mode) {
            RoutingMode.BEELINE -> return destinations.map { calcDistance(origin, it).toFloat() }.toFloatArray()
            RoutingMode.GRAPHHOPPER -> {
                if (origin !is RealLocation) {
                    return destinations.map { calcDistanceBeeline(origin, it).toFloat() }.toFloatArray()
                }

                return FloatArray(destinations.size) {
                    val destination = destinations[it]

                    if (destination !is RealLocation) {
                        calcDistanceBeeline(origin, destination).toFloat()
                    } else {
                        val od = UnorderedODPair(origin, destination)
                        cache.get(od)
                    }
                }
            }
        }
    }

    /**
     * Calculate the distance between origin and destination
     * @param origin Origin
     * @param destination Destination
     * @return Distance
     */
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

                val rsp = routeWithCar(origin as RealLocation, destination as RealLocation, hopper!!)
                if (rsp.hasErrors()) {
                    logger.warn(
                        "Could not route from ${origin.latlonCoord} to ${destination.latlonCoord}. Fall back to Beeline."
                    )

                    for (error in rsp.errors) {
                        if (error is PointNotFoundException) {
                            val unreachableCoord = if (error.pointIndex == 0) {
                                unRoutableLocs.add(origin)
                                origin.latlonCoord
                            } else {
                                unRoutableLocs.add(destination)
                                destination.latlonCoord
                            }
                            logger.warn(
                                "Because Point $unreachableCoord is unreachable."
                            )
                        }
                    }

                    return calcDistanceBeeline(origin, destination)
                } else {
                    return rsp.best.distance
                }
            }
        }
    }

    /**
     * Store cache in file
     */
    fun toOOMCache () {
        if (cachePath != null) {
            Files.createDirectories(cachePath!!.parent)
            val fos = FileOutputStream(cachePath!!.toFile())
            val oos = ObjectOutputStream(fos)
            oos.writeObject(formatForCache(cache.asMap()))
            oos.close()
        } else {
            if (mode != RoutingMode.BEELINE) {
                logger.warn("Couldn't save routed distances because routing cache has not store path.")
            }
        }
    }

    /**
     * Load cache from file
     */
    private fun fillFromOOMCache (cachePath: Path, locations: List<LocationOption>) {
        val fis = FileInputStream(cachePath.toFile())
        val ois = ObjectInputStream(fis)
        val cacheData = ois.readObject() as OOMCacheFormat

        // Find locations for coords
        val allCoords = cacheData.dCoords.toSet().union(cacheData.oCoords.toSet())
        val coordLocMap = mutableMapOf<Coordinate, LocationOption>()
        for (coord in allCoords) {
            if (coordLocMap.containsKey(coord)) { continue }
            for (location in locations) {
                if ((coord.x == location.latlonCoord.x) and (coord.y == location.latlonCoord.y)) {
                    coordLocMap[coord] = location
                    break
                }
            }
        }

        // Fill cache
        for (i in 0 until cacheData.oCoords.size) {
            val o = coordLocMap[cacheData.oCoords[i]]!!
            val d = coordLocMap[cacheData.dCoords[i]]!!
            val od = UnorderedODPair(o, d)
            val distance = cacheData.distances[i]
            cache.put(od, distance)
        }
    }

    /**
     * File storage format of the cache
     */
    private class OOMCacheFormat  (
        val oCoords: Array<Coordinate>,
        val dCoords: Array<Coordinate>,
        val distances: Array<Float>
    ) : java.io.Serializable

    /**
     * Format cache to the storage format
     */
    private fun formatForCache(table: ConcurrentMap<UnorderedODPair, Float>) : OOMCacheFormat {
        val oCoords = table.keys.map { it.first.latlonCoord }.toTypedArray()
        val dCoords = table.keys.map { it.second.latlonCoord }.toTypedArray()
        val distances = table.values.toTypedArray()
        return OOMCacheFormat(oCoords, dCoords, distances)
    }
}
