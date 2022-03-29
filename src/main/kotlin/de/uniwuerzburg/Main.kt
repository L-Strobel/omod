package de.uniwuerzburg

import kotlin.system.measureTimeMillis

fun main() {
    val gamg = Gamg("C:/Users/strobel/Projekte/esmregio/gamg/Buildings.csv", 500.0)
    val elapsed = measureTimeMillis { gamg.run(10000, safeToJson = true) }
    println(elapsed / 1000.0)
}