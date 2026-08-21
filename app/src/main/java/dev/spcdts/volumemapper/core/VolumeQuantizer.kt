package dev.spcdts.volumemapper.core

import kotlin.math.abs
import kotlin.math.roundToInt

enum class VolumeQuantizationMode {
    INDEX,
    DECIBELS,
}

/** The basis records whether a requested dB conversion had to fall back to index spacing. */
enum class QuantizationBasis {
    INDEX,
    DECIBELS,
}

data class QuantizedVolume(
    val index: Int,
    val requestedNormalized: Double,
    val effectiveNormalized: Double,
    val effectiveDecibels: Double?,
    val basis: QuantizationBasis,
)

/** Converts a continuous 0..1 target into the discrete range reported by AudioManager. */
object VolumeQuantizer {
    fun quantize(
        targetNormalized: Double,
        range: RouteVolumeRange,
        mode: VolumeQuantizationMode,
        muteAtZero: Boolean = true,
    ): QuantizedVolume {
        require(targetNormalized.isFinite()) { "targetNormalized must be finite" }
        val target = targetNormalized.coerceIn(0.0, 1.0)

        if (mode == VolumeQuantizationMode.DECIBELS && hasUsableDbScale(range)) {
            val index = quantizeByDecibels(target, range, muteAtZero)
            return QuantizedVolume(
                index = index,
                requestedNormalized = target,
                effectiveNormalized = normalizedForIndex(
                    index,
                    range,
                    VolumeQuantizationMode.DECIBELS,
                ),
                effectiveDecibels = range.decibelsAt(index),
                basis = QuantizationBasis.DECIBELS,
            )
        }

        val index = quantizeByIndex(target, range)
        return QuantizedVolume(
            index = index,
            requestedNormalized = target,
            effectiveNormalized = range.normalizedIndex(index),
            effectiveDecibels = range.decibelsAt(index),
            basis = QuantizationBasis.INDEX,
        )
    }

    /** Converts a platform readback index to the same normalized space used by mapping curves. */
    fun normalizedForIndex(
        index: Int,
        range: RouteVolumeRange,
        mode: VolumeQuantizationMode,
    ): Double {
        require(range.contains(index)) { "index is outside this route's range" }
        if (mode != VolumeQuantizationMode.DECIBELS || !hasUsableDbScale(range)) {
            return range.normalizedIndex(index)
        }

        val db = range.decibelsAt(index)
            ?: return if (index == range.minIndex) 0.0 else range.normalizedIndex(index)
        val audible = audibleSamples(range)
        val minDb = audible.first().second
        val maxDb = audible.last().second
        return ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
    }

    private fun quantizeByIndex(target: Double, range: RouteVolumeRange): Int {
        if (range.maxIndex == range.minIndex) return range.minIndex
        val offset = (target * (range.maxIndex - range.minIndex)).roundToInt()
        return (range.minIndex + offset).coerceIn(range.minIndex, range.maxIndex)
    }

    private fun quantizeByDecibels(
        target: Double,
        range: RouteVolumeRange,
        muteAtZero: Boolean,
    ): Int {
        if (target <= 0.0 && muteAtZero) return range.minIndex
        val audible = audibleSamples(range)
        if (target >= 1.0) return audible.last().first
        val minDb = audible.first().second
        val maxDb = audible.last().second
        val targetDb = minDb + target * (maxDb - minDb)

        // Prefer the quieter (lower-index) value on an exact tie.
        return audible.minWithOrNull(
            compareBy<Pair<Int, Double>> { abs(it.second - targetDb) }
                .thenBy { it.first },
        )!!.first
    }

    private fun hasUsableDbScale(range: RouteVolumeRange): Boolean {
        val audible = audibleSamples(range)
        return audible.size >= 2 && audible.last().second - audible.first().second > DB_EPSILON
    }

    private fun audibleSamples(range: RouteVolumeRange): List<Pair<Int, Double>> {
        if (range.decibelsByIndex.isEmpty()) return emptyList()
        return range.decibelsByIndex.mapIndexedNotNull { offset, db ->
            db?.let { range.minIndex + offset to it }
        }
    }

    private const val DB_EPSILON = 1e-6
}
