package dev.spcdts.volumemapper.core

/** Broad route classes are stable across OEM-specific AudioDeviceInfo type additions. */
enum class AudioRouteType {
    BUILT_IN_SPEAKER,
    WIRED_HEADSET,
    BLUETOOTH_A2DP,
    BLUETOOTH_LE,
    USB,
    HDMI,
    REMOTE,
    UNKNOWN,
}

enum class AbsoluteVolumeSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

/** How confidently the platform identified the route that currently carries media audio. */
enum class RouteConfidence {
    /** Android reported exactly one device for media [android.media.AudioAttributes]. */
    CONFIRMED,

    /** The route was selected from connected outputs or an ambiguous multi-device result. */
    HEURISTIC,
}

data class AudioRouteDescriptor(
    val stableId: String,
    val type: AudioRouteType,
    val productName: String? = null,
    val absoluteVolumeSupport: AbsoluteVolumeSupport = AbsoluteVolumeSupport.UNKNOWN,
    val confidence: RouteConfidence = RouteConfidence.HEURISTIC,
) {
    init {
        require(stableId.isNotBlank()) { "stableId cannot be blank" }
    }
}

/**
 * Runtime-discovered volume range for one route. [decibelsByIndex], when present, is indexed from
 * [minIndex]. A null entry represents mute/negative infinity or an unavailable platform reading.
 */
data class RouteVolumeRange(
    val minIndex: Int,
    val maxIndex: Int,
    val decibelsByIndex: List<Double?> = emptyList(),
) {
    val indexCount: Int = maxIndex - minIndex + 1

    init {
        require(maxIndex >= minIndex) { "maxIndex cannot be below minIndex" }
        require(decibelsByIndex.isEmpty() || decibelsByIndex.size == indexCount) {
            "decibelsByIndex must be empty or contain one entry per index"
        }

        var previous: Double? = null
        decibelsByIndex.forEachIndexed { offset, decibels ->
            if (decibels != null) {
                require(decibels.isFinite()) {
                    "dB at index ${minIndex + offset} must be finite or null"
                }
                val previousDecibels = previous
                if (previousDecibels != null) {
                    require(decibels + DB_EPSILON >= previousDecibels) {
                        "dB values must not decrease as the index increases"
                    }
                }
                previous = decibels
            }
        }
    }

    fun contains(index: Int): Boolean = index in minIndex..maxIndex

    fun decibelsAt(index: Int): Double? {
        require(contains(index)) { "index is outside this route's range" }
        return decibelsByIndex.takeIf { it.isNotEmpty() }?.get(index - minIndex)
    }

    fun normalizedIndex(index: Int): Double {
        require(contains(index)) { "index is outside this route's range" }
        if (maxIndex == minIndex) return 0.0
        return (index - minIndex).toDouble() / (maxIndex - minIndex).toDouble()
    }

    companion object {
        private const val DB_EPSILON = 1e-6
    }
}

data class RouteVolumeSnapshot(
    val route: AudioRouteDescriptor,
    val range: RouteVolumeRange,
    val currentIndex: Int,
    val observedAtElapsedMillis: Long,
) {
    init {
        require(range.contains(currentIndex)) { "currentIndex is outside the route range" }
        require(observedAtElapsedMillis >= 0L) { "observedAtElapsedMillis cannot be negative" }
        require(
            route.confidence == RouteConfidence.CONFIRMED || range.decibelsByIndex.isEmpty(),
        ) { "A heuristic route cannot safely expose a device-specific dB table" }
    }
}
