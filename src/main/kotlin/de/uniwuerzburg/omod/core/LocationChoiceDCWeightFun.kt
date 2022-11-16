package de.uniwuerzburg.omod.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln

// Expected meta information
@Serializable
sealed class LocationChoiceDCWeightFun {
    abstract val coeffOFFICE: Double
    abstract val coeffSHOP: Double
    abstract val coeffSCHOOL: Double
    abstract val coeffUNI: Double

    abstract fun deterrenceFunction(distance: Double) : Double

    open fun calcFor(destination: LocationOption, distance: Double) : Double {
        // Log is undefined for 0
        val distanceAdj = if (distance == 0.0) {
            Double.MIN_VALUE
        } else {
            distance / 1000
        }
        val v = deterrenceFunction(distanceAdj)
        val destinationAttraction = calcForNoOrigin(destination)
        return  destinationAttraction * exp(v)
    }

    open fun calcForNoOrigin(destination: LocationOption) : Double {
        return destination.nBuilding +
               coeffOFFICE * destination.nOffices +
               coeffSHOP * destination.nShops +
               coeffSCHOOL * destination.nSchools +
               coeffUNI * destination.nUnis
    }
}

@Suppress("unused")
object ByPopulation : LocationChoiceDCWeightFun() {
    override val coeffOFFICE: Double
        get() {
            throw NotImplementedError()
        }
    override val coeffSHOP: Double
        get() {
            throw NotImplementedError()
        }
    override val coeffSCHOOL: Double
        get() {
            throw NotImplementedError()
        }
    override val coeffUNI: Double
        get() {
            throw NotImplementedError()
        }

    override fun calcForNoOrigin(destination: LocationOption): Double {
        return destination.population
    }

    override fun deterrenceFunction(distance: Double): Double {
        throw NotImplementedError()
    }

    override fun calcFor(destination: LocationOption, distance: Double): Double {
        throw NotImplementedError()
    }
}

@Serializable
@SerialName("LogNorm")
@Suppress("unused")
class LogNormDCUtil (
    override val coeffOFFICE: Double,
    override val coeffSHOP: Double,
    override val coeffSCHOOL: Double,
    override val coeffUNI: Double,
    // For deterrence function
    private val coeff0: Double,
    private val coeff1: Double,
    ) : LocationChoiceDCWeightFun( ) {

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * ln(distance) * ln(distance) + coeff1 * ln(distance)
    }
}

@Serializable
@SerialName("CombinedPowerExpon")
@Suppress("unused")
data class CombinedDCUtil(
    override val coeffOFFICE: Double,
    override val coeffSHOP: Double,
    override val coeffSCHOOL: Double,
    override val coeffUNI: Double,
    // For deterrence function
    private val coeff0: Double,
    private val coeff1: Double,
) : LocationChoiceDCWeightFun( ) {

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * distance  + coeff1 * ln(distance)
    }
}