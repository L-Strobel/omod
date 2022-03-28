package de.uniwuerzburg

import kotlin.system.measureTimeMillis
import org.apache.commons.math3.distribution.WeibullDistribution
import org.apache.commons.math3.distribution.LogNormalDistribution
import org.jetbrains.kotlinx.multik.api.*
import org.jetbrains.kotlinx.multik.ndarray.data.*
import org.jetbrains.kotlinx.multik.ndarray.operations.sum
import org.jetbrains.kotlinx.multik.ndarray.operations.sumBy

fun main() {
    val gamg = Gamg("C:/Users/strobel/Projekte/esmregio/gamg/Buildings.csv", 500.0)
    //val elapsed = measureTimeMillis { gamg.run(50000,  true) }
    //println(elapsed / 1000.0)
}