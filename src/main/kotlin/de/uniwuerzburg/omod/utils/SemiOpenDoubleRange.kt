package de.uniwuerzburg.omod.utils

/**
 * Iterate over range with double bounds and with a double step size.
 */
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