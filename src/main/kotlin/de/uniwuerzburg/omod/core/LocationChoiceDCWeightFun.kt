package de.uniwuerzburg.omod.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.exp
import kotlin.math.ln

/**
 * Destination choice model. Parent class.
 */
@Serializable
sealed class LocationChoiceDCWeightFun {
    abstract val coeffResidentialArea: Double
    abstract val coeffCommercialArea: Double
    abstract val coeffIndustrialArea: Double
    abstract val coeffOtherArea: Double
    abstract val coeffOfficeUnits: Double
    abstract val coeffShopUnits: Double
    abstract val coeffSchoolUnits: Double
    abstract val coeffUniUnits: Double
    abstract val coeffBuildingUnits: Double
    abstract val coeffResidentialUnits: Double
    abstract val coeffCommercialUnits: Double
    abstract val coeffIndustrialUnits: Double

    /**
     * Calculates the natural logarithm of the deterrence function given the distance from the origin.
     *
     * @param distance The distance between origin and destination
     * @return ln(f(d))
     */
    abstract fun deterrenceFunction(distance: Double) : Double

    /**
     * Calculates the probabilistic weight of a destination given the distance from the origin.
     *
     * @param destination The destination the weight will be calculated for
     * @param distance The distance between origin and destination
     * @return probabilistic weight
     */
    open fun calcFor(destination: RealLocation, distance: Double) : Double {
        // Log is undefined for 0
        val distanceAdj = if (distance == 0.0) {
            Double.MIN_VALUE
        } else {
            distance / 1000
        }
        val fd = deterrenceFunction(distanceAdj)
        val attraction = calcForNoOrigin(destination)
        return attraction * exp(fd)
    }

    /**
     * Calculates the probabilistic weight of a destination without knowledge of the origin.
     * Used for the distribution of HOME locations and for destination choice within a routing cell,
     * where the distance difference between the buildings is neglected.
     *
     * @param destination The destination the weight will be calculated for
     * @return probabilistic weight
     */
    open fun calcForNoOrigin(destination: RealLocation) : Double {
        return 1 + coeffResidentialArea * destination.areaResidential +
               coeffCommercialArea * destination.areaCommercial +
               coeffIndustrialArea * destination.areaIndustrial +
               coeffOtherArea * destination.areaOther +
               coeffOfficeUnits * destination.nOffices +
               coeffShopUnits * destination.nShops +
               coeffSchoolUnits * destination.nSchools +
               coeffUniUnits * destination.nUnis +
               coeffBuildingUnits * destination.nBuilding +
               coeffResidentialUnits * destination.nResidential +
               coeffCommercialUnits * destination.nCommercial +
               coeffIndustrialUnits * destination.nIndustrial
    }
}

/**
 * Destination choice function implementation for the HOME location distribution when census data is given.
 */
@Suppress("unused")
object ByPopulation: LocationChoiceDCWeightFun () {
    override val coeffResidentialArea: Double get() { throw NotImplementedError() }
    override val coeffCommercialArea: Double get() { throw NotImplementedError() }
    override val coeffIndustrialArea: Double get() { throw NotImplementedError() }
    override val coeffOtherArea: Double get() { throw NotImplementedError() }
    override val coeffOfficeUnits: Double get() { throw NotImplementedError() }
    override val coeffShopUnits: Double get() { throw NotImplementedError() }
    override val coeffSchoolUnits: Double get() { throw NotImplementedError() }
    override val coeffUniUnits: Double get() { throw NotImplementedError() }
    override val coeffBuildingUnits: Double get() { throw NotImplementedError() }
    override val coeffResidentialUnits: Double get() { throw NotImplementedError() }
    override val coeffCommercialUnits: Double get() { throw NotImplementedError() }
    override val coeffIndustrialUnits: Double get() { throw NotImplementedError() }

    override fun calcForNoOrigin(destination: RealLocation): Double {
        return destination.population
    }

    override fun deterrenceFunction(distance: Double): Double {
        throw NotImplementedError()
    }

    override fun calcFor(destination: RealLocation, distance: Double): Double {
        throw NotImplementedError()
    }
}

/**
 * Destination choice function implementation for the HOME location distribution when census data is not given.
 */
@Serializable
@SerialName("PureAttraction")
@Suppress("unused")
class PureAttraction (
    override val coeffResidentialArea: Double,
    override val coeffCommercialArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffIndustrialUnits: Double,
    ) : LocationChoiceDCWeightFun( ) {

    override fun deterrenceFunction(distance: Double): Double {
        throw NotImplementedError()
    }

    override fun calcFor(destination: RealLocation, distance: Double): Double {
        throw NotImplementedError()
    }
}

/**
 * Destination choice function with log-normal functional form.
 *
 * Deterrence function:
 * ln(f(d)) = a ln^2(d) + b ln(d)
 */
@Serializable
@SerialName("LogNorm")
@Suppress("unused")
class LogNormDCUtil (
    override val coeffResidentialArea: Double,
    override val coeffCommercialArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffIndustrialUnits: Double,
    // For deterrence function
    private val coeff0: Double,
    private val coeff1: Double,
    ) : LocationChoiceDCWeightFun( ) {

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * ln(distance) * ln(distance) + coeff1 * ln(distance)
    }
}

/**
 * Destination choice function with log-normal functional form.
 *
 * Deterrence function:
 * ln(f(d)) = a*ln^2(d) + b*ln(d) + c*d
 */
@Serializable
@SerialName("LogNormPower")
@Suppress("unused")
class LogNormPowerDCUtil (
    override val coeffResidentialArea: Double,
    override val coeffCommercialArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffIndustrialUnits: Double,
    // For deterrence function
    private val coeff0: Double,
    private val coeff1: Double,
    private val coeff2: Double
) : LocationChoiceDCWeightFun( ) {
    @Transient
    private var maxValidDistance: Double = Double.MAX_VALUE

    init {
        // Determine minimum of deterrence function
        val earthCircumference = 40_075.017 // Unit: km
        var previousValue = Double.MAX_VALUE
        for (distance in 1 until (earthCircumference / 2).toInt()) {
            val value = deterrenceFunction(distance.toDouble())

            // Check if minimum found
            if (previousValue < value) {
                maxValidDistance = (distance - 1).toDouble()
                break
            } else {
                previousValue = value
            }
        }
    }

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * ln(distance) * ln(distance) + coeff1 * ln(distance) + coeff2 * distance
    }

    override fun calcFor(destination: RealLocation, distance: Double): Double {
        return  if (distance / 1000 > maxValidDistance) {
            return 0.0
        } else {
            super.calcFor(destination, distance)
        }
    }
}

/**
 * Destination choice function with power-expon functional form.
 *
 * Deterrence function:
 * ln(f(d)) = a*d + b*ln(d)
 */
@Serializable
@SerialName("CombinedPowerExpon")
@Suppress("unused")
data class CombinedDCUtil(
    override val coeffResidentialArea: Double,
    override val coeffCommercialArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffIndustrialUnits: Double,
    // For deterrence function
    private val coeff0: Double,
    private val coeff1: Double,
) : LocationChoiceDCWeightFun( ) {

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * distance  + coeff1 * ln(distance)
    }
}