package de.uniwuerzburg.omod.core

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

object ProgressBar {
    fun show(progress: Double) : String {
        val ticks = (50 * progress).toInt()
        val bar = "[${"=".repeat(ticks)}${" ".repeat(50 - ticks)}]"
        val number = "%.2f".format(null, 100.0 * progress)
        return "$bar $number %"
    }
    fun done() : String {
        val bar = "[${"=".repeat(50)}]"
        return "$bar Done!"
    }
}
