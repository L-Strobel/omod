package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.Landuse
import de.uniwuerzburg.omod.core.models.RealLocation
import de.uniwuerzburg.omod.io.geojson.GeoJsonBuildingProperties
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.exp
import kotlin.math.ln

/**
 * Return unique IDs used for the destination choice functions.
 * These IDs are used to cache a location's attraction value, as calculated with the function,
 * inside the location itself.
 */
object IDDispenser {
    var nextID = 0

    fun next() : Int {
        val id = nextID
        nextID += 1
        return id
    }
}

/**
 * Destination choice model. Parent class.
 */
@Serializable
sealed class LocationChoiceDCWeightFun {
    abstract val coeffResidentialArea: Double
    abstract val coeffCommercialArea: Double
    abstract val coeffRetailArea: Double
    abstract val coeffIndustrialArea: Double
    abstract val coeffOfficeArea: Double
    abstract val coeffShopArea: Double
    abstract val coeffSchoolArea: Double
    abstract val coeffUniversityArea: Double
    abstract val coeffOtherArea: Double
    abstract val coeffOfficeUnits: Double
    abstract val coeffShopUnits: Double
    abstract val coeffSchoolUnits: Double
    abstract val coeffUniUnits: Double
    abstract val coeffPlaceOfWorshipUnits: Double
    abstract val coeffCafeUnits: Double
    abstract val coeffFastFoodUnits: Double
    abstract val coeffKinderGartenUnits: Double
    abstract val coeffTourismUnits: Double
    abstract val coeffBuildingUnits: Double
    abstract val coeffResidentialUnits: Double
    abstract val coeffCommercialUnits: Double
    abstract val coeffRetailUnits: Double
    abstract val coeffIndustrialUnits: Double

    @Transient
    val id: Int = IDDispenser.next()

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
        // Minimum distance where at which distance has an influence. The left side of the deterrence functions
        // are poorly fitted due to the maximum resolution in the MID being 500m.
        val distanceAdj = if (distance <= 750) {
            0.150
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
    fun calcForNoOrigin(destination: RealLocation) : Double {
        return destination.attractions[id]!!
    }

    open fun calcAttraction(properties: GeoJsonBuildingProperties) : Double {
        val area = properties.area
        val areaOffice = if (properties.number_offices > 0) area else 0.0
        val areaShop = if (properties.number_shops > 0) area else 0.0
        val areaSchool = if (properties.number_schools > 0) area else 0.0
        val areaUniversity = if (properties.number_universities > 0) area else 0.0

        val attractionLanduse = when( properties.landuse) {
            Landuse.RESIDENTIAL -> {
                coeffResidentialArea * area + coeffResidentialUnits * 1.0
            }
            Landuse.COMMERCIAL -> {
                coeffCommercialArea * area + coeffCommercialUnits * 1.0
            }
            Landuse.RETAIL -> {
                coeffRetailArea * area + coeffRetailUnits * 1.0
            }
            Landuse.INDUSTRIAL -> {
                coeffIndustrialArea * area + coeffIndustrialUnits * 1.0
            }
            else -> {
                coeffOtherArea * area
            }
        }

        return  1.0 +
                attractionLanduse +
                coeffOfficeArea * areaOffice +
                coeffShopArea * areaShop +
                coeffSchoolArea * areaSchool +
                coeffUniversityArea * areaUniversity +
                coeffOfficeUnits * properties.number_offices +
                coeffShopUnits * properties.number_shops +
                coeffSchoolUnits * properties.number_schools +
                coeffUniUnits * properties.number_universities +
                coeffPlaceOfWorshipUnits * properties.number_place_of_worship +
                coeffCafeUnits * properties.number_cafe +
                coeffFastFoodUnits * properties.number_fast_food +
                coeffKinderGartenUnits * properties.number_kindergarten +
                coeffTourismUnits * properties.number_tourism +
                coeffBuildingUnits * 1
    }
}

/**
 * Destination choice function implementation for the HOME location distribution when census data is given.
 */
@Suppress("unused")
object ByPopulation: LocationChoiceDCWeightFun () {
    override val coeffResidentialArea: Double get() { throw NotImplementedError() }
    override val coeffCommercialArea: Double get() { throw NotImplementedError() }
    override val coeffRetailArea: Double get() { throw NotImplementedError() }
    override val coeffIndustrialArea: Double get() { throw NotImplementedError() }
    override val coeffOfficeArea: Double get() { throw NotImplementedError() }
    override val coeffShopArea: Double get() { throw NotImplementedError() }
    override val coeffSchoolArea: Double get() { throw NotImplementedError() }
    override val coeffUniversityArea: Double get() { throw NotImplementedError() }
    override val coeffOtherArea: Double get() { throw NotImplementedError() }
    override val coeffOfficeUnits: Double get() { throw NotImplementedError() }
    override val coeffShopUnits: Double get() { throw NotImplementedError() }
    override val coeffSchoolUnits: Double get() { throw NotImplementedError() }
    override val coeffUniUnits: Double get() { throw NotImplementedError() }
    override val coeffPlaceOfWorshipUnits: Double get() { throw NotImplementedError() }
    override val coeffCafeUnits: Double get() { throw NotImplementedError() }
    override val coeffFastFoodUnits: Double get() { throw NotImplementedError() }
    override val coeffKinderGartenUnits: Double get() { throw NotImplementedError() }
    override val coeffTourismUnits: Double get() { throw NotImplementedError() }
    override val coeffBuildingUnits: Double get() { throw NotImplementedError() }
    override val coeffResidentialUnits: Double get() { throw NotImplementedError() }
    override val coeffCommercialUnits: Double get() { throw NotImplementedError() }
    override val coeffRetailUnits: Double get() { throw NotImplementedError() }
    override val coeffIndustrialUnits: Double get() { throw NotImplementedError() }

    override fun calcAttraction(properties: GeoJsonBuildingProperties): Double {
        return properties.population ?: 0.0
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
    override val coeffRetailArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOfficeArea: Double,
    override val coeffShopArea: Double,
    override val coeffSchoolArea: Double,
    override val coeffUniversityArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffPlaceOfWorshipUnits: Double,
    override val coeffCafeUnits: Double,
    override val coeffFastFoodUnits: Double,
    override val coeffKinderGartenUnits: Double,
    override val coeffTourismUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffRetailUnits: Double,
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
    override val coeffRetailArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOfficeArea: Double,
    override val coeffShopArea: Double,
    override val coeffSchoolArea: Double,
    override val coeffUniversityArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffPlaceOfWorshipUnits: Double,
    override val coeffCafeUnits: Double,
    override val coeffFastFoodUnits: Double,
    override val coeffKinderGartenUnits: Double,
    override val coeffTourismUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffRetailUnits: Double,
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
    override val coeffRetailArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOfficeArea: Double,
    override val coeffShopArea: Double,
    override val coeffSchoolArea: Double,
    override val coeffUniversityArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffPlaceOfWorshipUnits: Double,
    override val coeffCafeUnits: Double,
    override val coeffFastFoodUnits: Double,
    override val coeffKinderGartenUnits: Double,
    override val coeffTourismUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffRetailUnits: Double,
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
    override val coeffRetailArea: Double,
    override val coeffIndustrialArea: Double,
    override val coeffOfficeArea: Double,
    override val coeffShopArea: Double,
    override val coeffSchoolArea: Double,
    override val coeffUniversityArea: Double,
    override val coeffOtherArea: Double,
    override val coeffOfficeUnits: Double,
    override val coeffShopUnits: Double,
    override val coeffSchoolUnits: Double,
    override val coeffUniUnits: Double,
    override val coeffPlaceOfWorshipUnits: Double,
    override val coeffCafeUnits: Double,
    override val coeffFastFoodUnits: Double,
    override val coeffKinderGartenUnits: Double,
    override val coeffTourismUnits: Double,
    override val coeffBuildingUnits: Double,
    override val coeffResidentialUnits: Double,
    override val coeffCommercialUnits: Double,
    override val coeffRetailUnits: Double,
    override val coeffIndustrialUnits: Double,
    // For deterrence function
    private val coeff0: Double,
    private val coeff1: Double,
) : LocationChoiceDCWeightFun( ) {

    override fun deterrenceFunction(distance: Double) : Double {
        return coeff0 * distance  + coeff1 * ln(distance)
    }
}