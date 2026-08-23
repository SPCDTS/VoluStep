package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StepVolumeMapTest {
    @Test
    fun `map validates basis endpoints count and strict offsets`() {
        expectIllegalArgument { StepVolumeMap(0, listOf(0, 0)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(0)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(1, 4)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(0, 3)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(0, 2, 2, 4)) }
        expectIllegalArgument { StepVolumeMap(2, listOf(0, 1, 2, 3)) }
    }

    @Test
    fun `x coordinates are implicit uniform and offset evaluation interpolates`() {
        val map = StepVolumeMap(basisSpan = 20, offsets = listOf(0, 1, 4, 12, 20))

        assertEquals(4, map.pressCount)
        assertEquals(0.0, map.normalizedXAt(0), TOLERANCE)
        assertEquals(0.5, map.normalizedXAt(2), TOLERANCE)
        assertEquals(1.0, map.normalizedXAt(4), TOLERANCE)
        assertEquals(2.5, map.evaluateOffset(0.375), TOLERANCE)
        assertEquals(0.0, map.evaluateOffset(-1.0), TOLERANCE)
        assertEquals(20.0, map.evaluateOffset(2.0), TOLERANCE)
        expectIllegalArgument { map.normalizedXAt(5) }
        expectIllegalArgument { map.evaluateOffset(Double.NaN) }
    }

    @Test
    fun `continuous legacy curve is sampled and its floor and cap are expanded`() {
        val legacy = MappingCurve(
            listOf(
                MappingPoint(0.0, 0.2),
                MappingPoint(0.5, 0.3),
                MappingPoint(1.0, 0.6),
            ),
        )

        val sampled = StepVolumeMap.fromCurve(legacy, basisSpan = 40, pressCount = 4)

        assertEquals(listOf(0, 5, 10, 25, 40), sampled.offsets)
        val flat = MappingCurve(
            listOf(MappingPoint(0.0, 0.4), MappingPoint(1.0, 0.4)),
        )
        assertEquals(StepVolumeMap.linear(12, 3), StepVolumeMap.fromCurve(flat, 12, 3))
    }

    @Test
    fun `legacy plateau is projected to the closest strict integer sequence`() {
        val plateau = MappingCurve(
            listOf(
                MappingPoint(0.0, 0.0),
                MappingPoint(0.25, 0.0),
                MappingPoint(0.75, 0.0),
                MappingPoint(1.0, 1.0),
            ),
        )

        val sampled = StepVolumeMap.fromCurve(plateau, basisSpan = 15, pressCount = 5)

        assertEquals(0, sampled.offsets.first())
        assertEquals(15, sampled.offsets.last())
        assertStrictlyIncreasing(sampled.offsets)
    }

    @Test
    fun `changing press count resamples with deterministic strict projection`() {
        val original = StepVolumeMap(basisSpan = 20, offsets = listOf(0, 4, 20))

        val expanded = original.resample(4)

        assertEquals(4, expanded.pressCount)
        assertEquals(listOf(0, 2, 4, 12, 20), expanded.offsets)
        assertSame(expanded, expanded.resample(4))
        assertEquals(StepVolumeMap(basisSpan = 20, offsets = listOf(0, 20)), expanded.resample(1))
        expectIllegalArgument { original.resample(0) }
        expectIllegalArgument { original.resample(21) }
    }

    @Test
    fun `single offset edit fixes endpoints and clamps between neighbours`() {
        val original = StepVolumeMap(20, listOf(0, 3, 8, 14, 20))

        assertSame(original, original.withOffset(0, 10))
        assertSame(original, original.withOffset(4, 10))
        assertEquals(listOf(0, 3, 4, 14, 20), original.withOffset(2, -100).offsets)
        assertEquals(listOf(0, 3, 13, 14, 20), original.withOffset(2, 100).offsets)
        assertEquals(listOf(0, 3, 10, 14, 20), original.withOffset(2, 10).offsets)
        assertSame(original, original.withOffset(2, 8))
        expectIllegalArgument { original.withOffset(5, 10) }
    }

    @Test
    fun `touch edit pushes crossed neighbours while preserving the requested point`() {
        val original = StepVolumeMap(20, listOf(0, 3, 8, 14, 20))

        assertEquals(
            listOf(0, 1, 2, 3, 20),
            original.withOffsetPushing(stepIndex = 3, requestedOffset = -100).offsets,
        )
        assertEquals(
            listOf(0, 17, 18, 19, 20),
            original.withOffsetPushing(stepIndex = 1, requestedOffset = 100).offsets,
        )
        assertEquals(
            listOf(0, 3, 12, 14, 20),
            original.withOffsetPushing(stepIndex = 2, requestedOffset = 12).offsets,
        )
        assertSame(original, original.withOffsetPushing(stepIndex = 0, requestedOffset = 10))
        assertSame(original, original.withOffsetPushing(stepIndex = 4, requestedOffset = 10))
        expectIllegalArgument { original.withOffsetPushing(stepIndex = 5, requestedOffset = 10) }
    }

    @Test
    fun `rebase adopts a nonzero route range and its reduced effective count`() {
        val source = StepVolumeMap(
            basisSpan = 20,
            offsets = listOf(0, 1, 2, 3, 5, 8, 12, 16, 20),
        )

        val rebased = source.rebase(RouteVolumeRange(minIndex = 5, maxIndex = 9))

        assertEquals(4, rebased.basisSpan)
        assertEquals(4, rebased.pressCount)
        assertEquals(listOf(0, 1, 2, 3, 4), rebased.offsets)
        expectIllegalArgument { source.rebase(RouteVolumeRange(7, 7)) }
    }

    @Test
    fun `same span binding preserves every authored offset exactly`() {
        val source = StepVolumeMap(15, listOf(0, 1, 3, 9, 15))

        val bound = source.bind(RouteVolumeRange(0, 15))

        assertEquals(source.offsets, bound.indices)
        assertEquals(source.pressCount, bound.effectivePressCount)
    }

    @Test
    fun `zero to fifteen binding is strict and keeps fixed endpoints`() {
        val bound = StepVolumeMap.linear(150, 5).bind(RouteVolumeRange(0, 15))

        assertEquals(listOf(0, 3, 6, 9, 12, 15), bound.indices)
        assertEquals(5, bound.effectivePressCount)
        assertStrictlyIncreasing(bound.indices)
    }

    @Test
    fun `zero to one hundred fifty binding preserves fifty effective presses`() {
        val bound = StepVolumeMap.linear(150, 50).bind(RouteVolumeRange(0, 150))

        assertEquals(0, bound.indices.first())
        assertEquals(150, bound.indices.last())
        assertEquals(51, bound.indices.size)
        assertEquals(50, bound.effectivePressCount)
        assertTrue(bound.indices.zipWithNext().all { (left, right) -> right - left == 3 })
    }

    @Test
    fun `nonzero minimum binding offsets every projected target`() {
        val source = StepVolumeMap(10, listOf(0, 1, 2, 8, 10))

        val bound = source.bind(RouteVolumeRange(minIndex = 5, maxIndex = 9))

        assertEquals(listOf(5, 6, 7, 8, 9), bound.indices)
        assertEquals(4, bound.effectivePressCount)
    }

    @Test
    fun `route with fewer intervals first reduces effective press count`() {
        val source = StepVolumeMap(
            basisSpan = 20,
            offsets = listOf(0, 1, 2, 3, 5, 8, 12, 16, 20),
        )

        val bound = source.bind(RouteVolumeRange(minIndex = 5, maxIndex = 9))

        assertEquals(4, bound.effectivePressCount)
        assertEquals(listOf(5, 6, 7, 8, 9), bound.indices)
    }

    @Test
    fun `next step is strict and accepts an observed index between targets`() {
        val bound = StepVolumeMap(15, listOf(0, 3, 12, 15))
            .bind(RouteVolumeRange(0, 15))

        assertEquals(BoundVolumeStep(stepIndex = 2, index = 12), bound.nextStep(7, VolumeDirection.UP))
        assertEquals(BoundVolumeStep(stepIndex = 1, index = 3), bound.nextStep(7, VolumeDirection.DOWN))
        assertNull(bound.nextStep(15, VolumeDirection.UP))
        assertNull(bound.nextStep(0, VolumeDirection.DOWN))
        expectIllegalArgument { bound.nextStep(16, VolumeDirection.UP) }
    }

    @Test
    fun `fixed volume route has no effective or next step`() {
        val bound = StepVolumeMap.linear(150, 50).bind(RouteVolumeRange(7, 7))

        assertEquals(listOf(7), bound.indices)
        assertEquals(0, bound.effectivePressCount)
        assertNull(bound.nextStep(7, VolumeDirection.UP))
        assertNull(bound.nextStep(7, VolumeDirection.DOWN))
    }

    @Test
    fun `least squares projection is globally optimal and breaks ties toward lower indices`() {
        val targets = listOf(0.0, 1.5, 3.0)

        assertEquals(listOf(0, 1, 3), StrictIntegerProjection.project(targets, span = 3))

        val nonlinear = listOf(0.0, 3.8, 4.1, 10.0)
        val projected = StrictIntegerProjection.project(nonlinear, span = 10)
        val projectedError = squaredError(projected, nonlinear)
        val bruteForceMinimum = (1..8).minOf { first ->
            ((first + 1)..9).minOf { second ->
                squaredError(listOf(0, first, second, 10), nonlinear)
            }
        }
        assertEquals(bruteForceMinimum, projectedError, TOLERANCE)
    }

    private fun squaredError(values: List<Int>, targets: List<Double>): Double =
        values.zip(targets).sumOf { (value, target) ->
            val difference = value - target
            difference * difference
        }

    private fun assertStrictlyIncreasing(values: List<Int>) {
        assertTrue(values.zipWithNext().all { (left, right) -> right > left })
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
        const val TOLERANCE = 1e-9
    }
}
