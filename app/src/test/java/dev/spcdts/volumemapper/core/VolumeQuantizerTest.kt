package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VolumeQuantizerTest {
    @Test
    fun `route range supports non-zero minimum index`() {
        val range = RouteVolumeRange(minIndex = 5, maxIndex = 9)

        assertEquals(5, range.minIndex)
        assertEquals(5, range.indexCount)
        assertEquals(0.5, range.normalizedIndex(7), TOLERANCE)
        assertTrue(range.contains(9))
        assertFalse(range.contains(4))
        assertNull(range.decibelsAt(7))
    }

    @Test
    fun `route range validates dB count values and monotonic order`() {
        expectIllegalArgument {
            RouteVolumeRange(0, 2, decibelsByIndex = listOf(null, -20.0))
        }
        expectIllegalArgument {
            RouteVolumeRange(0, 2, decibelsByIndex = listOf(null, -10.0, -20.0))
        }
        expectIllegalArgument {
            RouteVolumeRange(0, 1, decibelsByIndex = listOf(null, Double.NaN))
        }
    }

    @Test
    fun `index quantization clamps and rounds across runtime range`() {
        val range = RouteVolumeRange(minIndex = 5, maxIndex = 9)

        assertEquals(5, quantizeIndex(-1.0, range).index)
        assertEquals(6, quantizeIndex(0.24, range).index)
        assertEquals(7, quantizeIndex(0.5, range).index)
        assertEquals(9, quantizeIndex(2.0, range).index)
    }

    @Test
    fun `dB quantization uses audible loudness spacing and preserves mute`() {
        val range = dbRange()

        val muted = VolumeQuantizer.quantize(0.0, range, VolumeQuantizationMode.DECIBELS)
        val middle = VolumeQuantizer.quantize(0.5, range, VolumeQuantizationMode.DECIBELS)
        val maximum = VolumeQuantizer.quantize(1.0, range, VolumeQuantizationMode.DECIBELS)

        assertEquals(0, muted.index)
        assertEquals(2, middle.index)
        assertEquals(-30.0, middle.effectiveDecibels!!, TOLERANCE)
        assertEquals(QuantizationBasis.DECIBELS, middle.basis)
        assertEquals(4, maximum.index)
    }

    @Test
    fun `dB quantization prefers quieter index on exact tie`() {
        val range = dbRange()
        val normalizedForMinusTwentyDb = 2.0 / 3.0

        val result = VolumeQuantizer.quantize(
            normalizedForMinusTwentyDb,
            range,
            VolumeQuantizationMode.DECIBELS,
        )

        assertEquals(2, result.index)
    }

    @Test
    fun `unusable dB data falls back to index quantization`() {
        val flatDbRange = RouteVolumeRange(
            minIndex = 0,
            maxIndex = 2,
            decibelsByIndex = listOf(null, -20.0, -20.0),
        )

        val result = VolumeQuantizer.quantize(
            0.75,
            flatDbRange,
            VolumeQuantizationMode.DECIBELS,
        )

        assertEquals(2, result.index)
        assertEquals(QuantizationBasis.INDEX, result.basis)
    }

    @Test
    fun `heuristic route without dB table forces index quantization`() {
        val route = AudioRouteDescriptor(
            stableId = "bluetooth-a2dp:42:test-headset",
            type = AudioRouteType.BLUETOOTH_A2DP,
            confidence = RouteConfidence.HEURISTIC,
        )
        val heuristicRange = RouteVolumeRange(minIndex = 0, maxIndex = 150)
        val snapshot = RouteVolumeSnapshot(route, heuristicRange, 75, 123L)

        val result = VolumeQuantizer.quantize(
            targetNormalized = 0.5,
            range = heuristicRange,
            mode = VolumeQuantizationMode.DECIBELS,
        )

        assertEquals(RouteConfidence.HEURISTIC, route.confidence)
        assertTrue(snapshot.range.decibelsByIndex.isEmpty())
        assertEquals(75, result.index)
        assertEquals(QuantizationBasis.INDEX, result.basis)
        expectIllegalArgument {
            RouteVolumeSnapshot(route, dbRange(), currentIndex = 2, observedAtElapsedMillis = 123L)
        }
    }

    @Test
    fun `readback normalization mirrors the requested quantization space`() {
        val range = dbRange()

        assertEquals(
            0.5,
            VolumeQuantizer.normalizedForIndex(2, range, VolumeQuantizationMode.DECIBELS),
            TOLERANCE,
        )
        assertEquals(
            0.75,
            VolumeQuantizer.normalizedForIndex(3, range, VolumeQuantizationMode.INDEX),
            TOLERANCE,
        )
        assertEquals(
            0.0,
            VolumeQuantizer.normalizedForIndex(0, range, VolumeQuantizationMode.DECIBELS),
            TOLERANCE,
        )
    }

    @Test
    fun `snapshot validates current range and elapsed timestamp`() {
        val route = AudioRouteDescriptor(
            stableId = "bluetooth-a2dp:42",
            type = AudioRouteType.BLUETOOTH_A2DP,
            productName = "Test headset",
            absoluteVolumeSupport = AbsoluteVolumeSupport.SUPPORTED,
            confidence = RouteConfidence.CONFIRMED,
        )
        val range = RouteVolumeRange(0, 15)
        val snapshot = RouteVolumeSnapshot(route, range, 8, 123L)

        assertEquals(8, snapshot.currentIndex)
        assertEquals(RouteConfidence.CONFIRMED, snapshot.route.confidence)
        expectIllegalArgument { RouteVolumeSnapshot(route, range, 16, 123L) }
        expectIllegalArgument { RouteVolumeSnapshot(route, range, 8, -1L) }
        expectIllegalArgument {
            AudioRouteDescriptor(stableId = " ", type = AudioRouteType.UNKNOWN)
        }
    }

    private fun quantizeIndex(target: Double, range: RouteVolumeRange): QuantizedVolume =
        VolumeQuantizer.quantize(target, range, VolumeQuantizationMode.INDEX)

    private fun dbRange(): RouteVolumeRange = RouteVolumeRange(
        minIndex = 0,
        maxIndex = 4,
        decibelsByIndex = listOf(null, -60.0, -30.0, -10.0, 0.0),
    )

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private companion object {
        const val TOLERANCE = 1e-8
    }
}
