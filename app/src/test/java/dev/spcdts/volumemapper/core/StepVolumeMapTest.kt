package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StepVolumeMapTest {
    @Test
    fun `construction rejects invalid integer control geometry`() {
        val invalidMaps: List<Pair<String, () -> Unit>> = listOf(
            "zero basis span" to { StepVolumeMap(basisSpan = 0, offsets = listOf(0, 0)) },
            "too few controls" to { StepVolumeMap(basisSpan = 4, offsets = listOf(0)) },
            "nonzero first offset" to { StepVolumeMap(basisSpan = 4, offsets = listOf(1, 4)) },
            "wrong final offset" to { StepVolumeMap(basisSpan = 4, offsets = listOf(0, 3)) },
            "duplicate offset" to { StepVolumeMap(basisSpan = 4, offsets = listOf(0, 2, 2, 4)) },
            "duplicate press position" to {
                StepVolumeMap(
                    basisSpan = 4,
                    pressCount = 2,
                    pressPositions = listOf(0, 1, 1),
                    offsets = listOf(0, 1, 4),
                )
            },
            "descending offset" to {
                StepVolumeMap(
                    basisSpan = 4,
                    pressCount = 2,
                    pressPositions = listOf(0, 1, 2),
                    offsets = listOf(0, 3, 2),
                )
            },
        )

        invalidMaps.forEach { (label, block) -> expectIllegalArgument(label, block) }
    }

    @Test
    fun `integer control coordinates interpolate and project optimally`() {
        val map = StepVolumeMap(
            basisSpan = 20,
            pressCount = 4,
            pressPositions = listOf(0, 1, 2, 4),
            offsets = listOf(0, 2, 6, 20),
        )

        assertEquals(listOf(0.0, 0.25, 0.5, 1.0), map.normalizedXs)
        assertEquals(4.0, map.evaluateOffset(0.375), TOLERANCE)
        assertEquals(13.0, map.evaluateOffset(0.75), TOLERANCE)
        assertEquals(listOf(0, 2, 6, 13, 20), map.bind(RouteVolumeRange(0, 20)).indices)

        assertEquals(
            listOf(0, 1, 3),
            StrictIntegerProjection.project(listOf(0.0, 1.5, 3.0), span = 3),
        )
        val nonlinearTargets = listOf(0.0, 3.8, 4.1, 10.0)
        val projected = StrictIntegerProjection.project(nonlinearTargets, span = 10)
        val bruteForceMinimum = (1..8).minOf { first ->
            ((first + 1)..9).minOf { second ->
                squaredError(listOf(0, first, second, 10), nonlinearTargets)
            }
        }
        assertEquals(bruteForceMinimum, squaredError(projected, nonlinearTargets), TOLERANCE)
    }

    @Test
    fun `changing press count reprojects x while preserving authored offsets`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 9,
            pressPositions = listOf(0, 2, 6, 9),
            offsets = listOf(0, 4, 11, 20),
        )

        val changed = original.withPressCount(5)

        assertEquals(5, changed.pressCount)
        assertEquals(listOf(0, 1, 3, 5), changed.pressPositions)
        assertEquals(original.offsets, changed.offsets)
        assertStrictlyIncreasing(changed.pressPositions)
        assertSame(changed, changed.withPressCount(5))
    }

    @Test
    fun `dragging an interior point pushes crossed neighbours on both integer axes`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 8,
            pressPositions = listOf(0, 2, 5, 7, 8),
            offsets = listOf(0, 3, 8, 14, 20),
        )

        val moved = original.moveControlPointPushing(
            controlPointIndex = 2,
            requestedPressPosition = 4,
            requestedOffset = 19,
        )

        assertEquals(4, moved.pressPositionAt(2))
        assertEquals(listOf(0, 3, 18, 19, 20), moved.offsets)
        assertStrictlyIncreasing(moved.pressPositions)
        assertStrictlyIncreasing(moved.offsets)
    }

    @Test
    fun `route binding stays strict while rebasing preserves bound targets`() {
        val source = StepVolumeMap(
            basisSpan = 4,
            pressCount = 2,
            pressPositions = listOf(0, 1, 2),
            offsets = listOf(0, 3, 4),
        )
        val range = RouteVolumeRange(minIndex = 5, maxIndex = 8)

        val before = source.bind(range)
        val rebased = source.rebase(range)

        assertEquals(listOf(5, 7, 8), before.indices)
        assertEquals(before.indices, rebased.bind(range).indices)
        assertEquals(listOf(0, 2, 3), rebased.offsets)

        val fullRange = StepVolumeMap.linear(150, 50).bind(RouteVolumeRange(0, 150))
        assertEquals(51, fullRange.indices.size)
        assertEquals(50, fullRange.effectivePressCount)
        assertTrue(fullRange.indices.zipWithNext().all { (left, right) -> right - left == 3 })

        val reducedRange = StepVolumeMap(
            basisSpan = 20,
            offsets = listOf(0, 1, 2, 3, 5, 8, 12, 16, 20),
        ).bind(RouteVolumeRange(5, 9))
        assertEquals(listOf(5, 6, 7, 8, 9), reducedRange.indices)
        assertEquals(4, reducedRange.effectivePressCount)

        val fixedRange = StepVolumeMap.linear(150, 50).bind(RouteVolumeRange(7, 7))
        assertEquals(listOf(7), fixedRange.indices)
        assertEquals(0, fixedRange.effectivePressCount)
        assertEquals(null, fixedRange.nextStep(7, VolumeDirection.UP))
        assertEquals(null, fixedRange.nextStep(7, VolumeDirection.DOWN))
    }

    @Test
    fun `segment insertion adds its integer midpoint without moving existing controls`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 10,
            pressPositions = listOf(0, 4, 9, 10),
            offsets = listOf(0, 4, 18, 20),
        )

        val inserted = original.insertControlPointAtSegment(0)

        assertEquals(listOf(0, 2, 4, 9, 10), inserted.pressPositions)
        assertEquals(listOf(0, 2, 4, 18, 20), inserted.offsets)
        assertEquals(
            original.pressPositions,
            inserted.pressPositions.filterIndexed { index, _ -> index != 1 },
        )
        assertEquals(
            original.offsets,
            inserted.offsets.filterIndexed { index, _ -> index != 1 },
        )
    }

    private fun assertStrictlyIncreasing(values: List<Int>) {
        assertTrue(values.zipWithNext().all { (left, right) -> right > left })
    }

    private fun expectIllegalArgument(label: String = "invalid geometry", block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException: $label")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun squaredError(values: List<Int>, targets: List<Double>): Double =
        values.zip(targets).sumOf { (value, target) ->
            val difference = value - target
            difference * difference
        }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
