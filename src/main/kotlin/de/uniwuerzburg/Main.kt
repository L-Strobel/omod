package de.uniwuerzburg

import kotlin.system.measureTimeMillis

fun main() {
    val gamg = Gamg("C:/Users/strobel/Projekte/PythonPkgs/valactimod/Buildings.csv", 500.0)
    val elapsed = measureTimeMillis { gamg.run(50000,  true) }
    println(elapsed / 1000.0)
}