package de.uniwuerzburg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln

// Expected meta information
@Serializable
sealed class LocationChoiceDCWeightFun {
    abstract val destActivity: ActivityType
    abstract fun calcFor(destination: LocationOption, distance: Double) : Double
}

@Serializable
@SerialName("LogNorm")
@Suppress("unused")
data class LogNormDCUtil (
    val coeff0: Double,
    val coeff1: Double,
    override val destActivity: ActivityType
) : LocationChoiceDCWeightFun () {
    override fun calcFor(destination: LocationOption, distance: Double): Double {
        // Log is undefined for 0
        val distanceAdj = if (distance == 0.0) {
            Double.MIN_VALUE
        } else {
            distance
        }
        val v = coeff0 * ln(distanceAdj) * ln(distanceAdj) + coeff1 * ln(distanceAdj)
        val choices = destination.getPriorWeightFor(destActivity)
        return  choices * exp(v)
    }
}

@Serializable
@SerialName("CombinedPowerExpon")
@Suppress("unused")
data class CombinedDCUtil(
    val coeff0: Double,
    val coeff1: Double,
    override val destActivity: ActivityType
) : LocationChoiceDCWeightFun () {
    override fun calcFor(destination: LocationOption, distance: Double): Double {
        // Log is undefined for 0
        val distanceAdj = if (distance == 0.0) {
            Double.MIN_VALUE
        } else {
            distance
        }
        val v = coeff0 * distanceAdj  + coeff1 * ln(distanceAdj)
        val choices = destination.getPriorWeightFor(destActivity)
        return choices * exp(v)
    }
}