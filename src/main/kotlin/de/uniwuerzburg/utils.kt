package de.uniwuerzburg

import com.graphhopper.GraphHopper


@Suppress("unused")
infix fun ClosedRange<Double>.step(step: Double): Iterable<Double> {
    require(start.isFinite())
    require(endInclusive.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next > endInclusive) null else next
    }
    return sequence.asIterable()
}
fun semiOpenDoubleRange(start: Double, end: Double, step: Double): Iterable<Double> {
    require(start.isFinite())
    require(end.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next >= end) null else next
    }
    return sequence.asIterable()
}
@Suppress("unused")
fun Boolean.toInt() = if (this) 1 else 0
fun Boolean.toDouble() = if (this) 1.0 else 0.0

/**
 * Stores routing information
 */
class RoutingCache(
    private val mode: RoutingMode,
    private val hopper: GraphHopper?
) {
    private val sizeLimit = 20_000 // This value caps the memory consumption roughly at 5 GB
    private val table: HashMap<LocationOption, HashMap<LocationOption, Float>> = MaxSizeHashMap(sizeLimit)

    fun getDistances(origin: LocationOption, destinations: List<LocationOption>) : FloatArray {
        if (origin is DummyLocation) {
            return destinations.map { calcDistanceBeeline(origin, it).toFloat() }.toFloatArray()
        }
        val oTable  = if (!table.containsKey(origin)) {
            table[origin] = MaxSizeHashMap(sizeLimit * 3)
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
