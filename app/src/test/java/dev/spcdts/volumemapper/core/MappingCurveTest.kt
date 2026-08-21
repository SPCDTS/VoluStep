package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MappingCurveTest {
    @Test
    fun `point rejects non-finite and out-of-range coordinates`() {
        expectIllegalArgument { MappingPoint(-0.01, 0.0) }
        expectIllegalArgument { MappingPoint(0.0, 1.01) }
        expectIllegalArgument { MappingPoint(Double.NaN, 0.0) }
    }

    @Test
    fun `curve validates endpoints ordering spacing and monotonicity`() {
        expectIllegalArgument {
            MappingCurve(listOf(MappingPoint(0.1, 0.0), MappingPoint(1.0, 1.0)))
        }
        expectIllegalArgument {
            MappingCurve(listOf(MappingPoint(0.0, 0.0), MappingPoint(0.99, 1.0)))
        }
        expectIllegalArgument {
            MappingCurve(
                listOf(
                    MappingPoint(0.0, 0.0),
                    MappingPoint(0.5, 0.8),
                    MappingPoint(1.0, 0.7),
                ),
            )
        }
        expectIllegalArgument {
            MappingCurve(
                listOf(
                    MappingPoint(0.0, 0.0),
                    MappingPoint(0.01, 0.2),
                    MappingPoint(1.0, 1.0),
                ),
                minimumXSpacing = 0.02,
            )
        }
    }

    @Test
    fun `evaluate interpolates each segment and clamps inputs`() {
        val curve = MappingCurve(
            listOf(
                MappingPoint(0.0, 0.1),
                MappingPoint(0.25, 0.2),
                MappingPoint(1.0, 0.8),
            ),
        )

        assertEquals(0.1, curve.evaluate(-2.0), TOLERANCE)
        assertEquals(0.15, curve.evaluate(0.125), TOLERANCE)
        assertEquals(0.4, curve.evaluate(0.5), TOLERANCE)
        assertEquals(0.8, curve.evaluate(3.0), TOLERANCE)
        expectIllegalArgument { curve.evaluate(Double.NaN) }
    }

    @Test
    fun `inverse interpolates and applies requested plateau bias`() {
        val curve = MappingCurve(
            listOf(
                MappingPoint(0.0, 0.0),
                MappingPoint(0.25, 0.2),
                MappingPoint(0.6, 0.2),
                MappingPoint(1.0, 1.0),
            ),
        )

        assertEquals(0.25, curve.inverse(0.2, InverseBias.LOWER), TOLERANCE)
        assertEquals(0.425, curve.inverse(0.2, InverseBias.MIDPOINT), TOLERANCE)
        assertEquals(0.6, curve.inverse(0.2, InverseBias.UPPER), TOLERANCE)
        assertEquals(0.8, curve.inverse(0.6), TOLERANCE)
        assertEquals(0.0, curve.inverse(-1.0), TOLERANCE)
        assertEquals(1.0, curve.inverse(2.0), TOLERANCE)
    }

    @Test
    fun `movePoint constrains interior x and y without breaking neighbours`() {
        val curve = MappingCurve(
            points = listOf(
                MappingPoint(0.0, 0.0),
                MappingPoint(0.3, 0.2),
                MappingPoint(0.7, 0.8),
                MappingPoint(1.0, 1.0),
            ),
            minimumXSpacing = 0.1,
        )

        val movedRight = curve.movePoint(index = 1, requestedX = 0.95, requestedY = 0.95)
        assertEquals(0.6, movedRight.points[1].x, TOLERANCE)
        assertEquals(0.8, movedRight.points[1].y, TOLERANCE)

        val movedLeft = curve.movePoint(index = 2, requestedX = -1.0, requestedY = -1.0)
        assertEquals(0.4, movedLeft.points[2].x, TOLERANCE)
        assertEquals(0.2, movedLeft.points[2].y, TOLERANCE)
    }

    @Test
    fun `movePoint fixes endpoint x but allows constrained floor and cap`() {
        val curve = MappingCurve(
            listOf(
                MappingPoint(0.0, 0.0),
                MappingPoint(0.5, 0.4),
                MappingPoint(1.0, 1.0),
            ),
        )

        val floor = curve.movePoint(0, requestedX = 0.9, requestedY = 0.2)
        assertEquals(MappingPoint(0.0, 0.2), floor.points.first())

        val capped = floor.movePoint(2, requestedX = 0.1, requestedY = 0.7)
        assertEquals(MappingPoint(1.0, 0.7), capped.points.last())

        val constrained = capped.movePoint(0, requestedX = 0.0, requestedY = 0.9)
        assertEquals(0.4, constrained.points.first().y, TOLERANCE)
    }

    @Test
    fun `add and remove point preserve a monotonic curve`() {
        val original = MappingCurve.linear()
        val added = original.addPoint(0.6, requestedY = 0.99)

        assertEquals(original.points.size + 1, added.points.size)
        assertEquals(0.75, added.points[3].y, TOLERANCE)
        assertEquals(original, added.removePoint(3))
        expectIllegalArgument { original.addPoint(0.251) }
        expectIllegalArgument { original.removePoint(0) }
    }

    @Test
    fun `integral is exact across segments tails and reversed bounds`() {
        val curve = MappingCurve.linear()

        assertEquals(0.5, curve.integral(0.0, 1.0), TOLERANCE)
        assertEquals(1.5, curve.integral(-1.0, 2.0), TOLERANCE)
        assertEquals(-0.5, curve.integral(1.0, 0.0), TOLERANCE)
        assertEquals(0.0, curve.integral(0.4, 0.4), TOLERANCE)
    }

    @Test
    fun `all presets are valid and communicate distinct intentions`() {
        val curves = MappingPreset.entries.associateWith { it.createCurve() }

        curves.values.forEach { curve ->
            assertEquals(0.0, curve.points.first().x, TOLERANCE)
            assertEquals(1.0, curve.points.last().x, TOLERANCE)
            assertTrue(curve.points.zipWithNext().all { (left, right) -> right.y >= left.y })
        }
        assertTrue(
            curves.getValue(MappingPreset.LOW_VOLUME_FINE).evaluate(0.5) <
                curves.getValue(MappingPreset.LINEAR).evaluate(0.5),
        )
        assertEquals(
            0.45,
            curves.getValue(MappingPreset.NIGHT_CAP).evaluate(1.0),
            TOLERANCE,
        )
    }

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
