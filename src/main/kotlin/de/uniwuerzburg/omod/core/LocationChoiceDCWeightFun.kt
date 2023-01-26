package de.uniwuerzburg.omod.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln

// Expected meta information
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

    abstract fun deterrenceFunction(distance: Double) : Double

    open fun calcFor(destination: RealLocation, distance: Double) : Double {
        // Log is undefined for 0
        val distanceAdj = if (distance == 0.0) {
            Double.MIN_VALUE
        } else {
            distance / 1000
        }
        val fd = deterrenceFunction(distanceAdj)
        val attraction = calcForNoOrigin(destination)
        return exp(attraction + fd)
    }

    open fun calcForNoOrigin(destination: RealLocation) : Double {
        return coeffResidentialArea * destination.areaResidential +
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

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * ln(distance) * ln(distance) + coeff1 * ln(distance) + coeff2 * distance
    }
}

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