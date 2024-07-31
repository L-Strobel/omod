package de.uniwuerzburg.omod.utils

import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.*

/**
 * Separates a geometry into many envelopes. Used to quickly check if many smaller objects are within it.
 * Returns a set of envelopes that are either definitely within, definitely outside, or maybe inside the geometry.
 *
 * The algorithm works by creating a regular grid of the bounding box of the geometry and then testing each cell
 * individually. If an envelope is not completely within or disjoint from the geometry it is split into multiple
 * smaller envelopes. This process is repeated for all grid resolution defined in *resolutions*
 *
 * @param geometry Large geometry
 * @param resolutions Sizes of the regular grid that tested.
 * @param geometryFactory Geometry factory to use
 * @param ifNot Function that is applied to envelopes completely disjoint from the geometry
 * @param ifDoes Function that is applied to envelopes completely within the geometry
 * @param ifUnsure Function that is applied to envelopes that intersect the geometry but not completely
 */
fun fastCovers(geometry: Geometry, resolutions: List<Double>, geometryFactory: GeometryFactory,
               ifNot: (Envelope) -> Unit, ifDoes: (Envelope) -> Unit, ifUnsure: (Envelope) -> Unit) {
    val envelope = geometry.envelopeInternal!!
    val resolution = resolutions.first()
    for (x in semiOpenDoubleRange(envelope.minX, envelope.maxX, resolution)) {
        for (y in semiOpenDoubleRange(envelope.minY, envelope.maxY, resolution)) {
            val smallEnvelope = Envelope(x, min(x + resolution, envelope.maxX), y, min(y + resolution, envelope.maxY))
            val smallGeom = geometryFactory.toGeometry(smallEnvelope)

            if (geometry.disjoint(smallGeom)) {
                ifNot(smallEnvelope)
            } else if (geometry.contains(smallGeom)) {
                ifDoes(smallEnvelope)
            } else {
                val newResolutions = resolutions.drop(1)
                if (newResolutions.isEmpty()) {
                    ifUnsure(smallEnvelope)
                } else {
                    fastCovers(geometry.intersection(smallGeom), newResolutions, geometryFactory,
                        ifNot = ifNot, ifDoes = ifDoes,  ifUnsure = ifUnsure)
                }
            }
        }
    }
}
