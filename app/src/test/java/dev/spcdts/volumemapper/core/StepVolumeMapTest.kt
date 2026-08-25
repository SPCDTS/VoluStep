package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StepVolumeMapTest {
    @Test
    fun `construction evaluation and legacy import scenarios`() {
        `map validates basis endpoints count and strict offsets`()
        `legacy constructors keep uniform x and offset evaluation interpolates`()
        `integer press coordinates drive piecewise evaluation and nearest point lookup`()
        `continuous legacy curve is sampled and its floor and cap are expanded`()
        `legacy plateau is projected to the closest strict integer sequence`()
    }

    @Test
    fun `independent press and control count scenarios`() {
        `changing press count strictly reprojects integer x`()
        `changing control point count keeps both axes integer and strict`()
        `adding points handles opposing horizontal and vertical gaps`()
        `single offset edit fixes endpoints and clamps between neighbours`()
    }

    @Test
    fun `integer x and y editing scenarios`() {
        `x and combined edits fix endpoints and preserve strict axes`()
        `touch edit pushes crossed neighbours while preserving the requested point`()
    }

    @Test
    fun `rebase and authored detail preservation scenarios`() {
        `rebase adopts a nonzero route range and its reduced effective count`()
        `rebase preserves the already bound press targets when integer controls would drift`()
        `same span binding preserves every authored offset exactly`()
        `fewer control points than press states are interpolated at every press`()
        `control points cannot outnumber integer press states`()
    }

    @Test
    fun `route projection scenarios`() {
        `press count participates in map identity independently of control points`()
        `zero to fifteen binding is strict and keeps fixed endpoints`()
        `zero to one hundred fifty binding preserves fifty effective presses`()
        `nonzero minimum binding offsets every projected target`()
        `route with fewer intervals first reduces effective press count`()
    }

    @Test
    fun `step navigation and optimal projection scenarios`() {
        `next step is strict and accepts an observed index between targets`()
        `fixed volume route has no effective or next step`()
        `least squares projection is globally optimal and breaks ties toward lower indices`()
    }

    @Test
    fun `selected segment insertion uses an integer midpoint without moving existing points`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 10,
            pressPositions = listOf(0, 4, 9, 10),
            offsets = listOf(0, 4, 18, 20),
        )

        val inserted = original.insertControlPointAtSegment(0)

        assertEquals(listOf(0, 2, 4, 9, 10), inserted.pressPositions)
        assertEquals(listOf(0, 2, 4, 18, 20), inserted.offsets)
        assertEquals(original.pressPositions, inserted.pressPositions.filterIndexed { index, _ ->
            index != 1
        })
        assertEquals(original.offsets, inserted.offsets.filterIndexed { index, _ -> index != 1 })
        assertStrictlyIncreasing(inserted.pressPositions)
        assertStrictlyIncreasing(inserted.offsets)

        val lowerOddMidpoint = StepVolumeMap(
            basisSpan = 20,
            pressCount = 10,
            pressPositions = listOf(0, 1, 6, 10),
            offsets = listOf(0, 1, 11, 20),
        ).insertControlPointAtSegment(1)
        assertEquals(listOf(0, 1, 3, 6, 10), lowerOddMidpoint.pressPositions)
        assertEquals(listOf(0, 1, 5, 11, 20), lowerOddMidpoint.offsets)

        val roundedInterpolatedOffset = StepVolumeMap(
            basisSpan = 6,
            pressCount = 6,
            pressPositions = listOf(0, 2, 6),
            offsets = listOf(0, 3, 6),
        ).insertControlPointAtSegment(0)
        assertEquals(listOf(0, 1, 2, 6), roundedInterpolatedOffset.pressPositions)
        assertEquals(listOf(0, 2, 3, 6), roundedInterpolatedOffset.offsets)

        val horizontallyDense = StepVolumeMap(
            basisSpan = 4,
            pressCount = 4,
            pressPositions = listOf(0, 1, 4),
            offsets = listOf(0, 1, 4),
        )
        val verticallyDense = StepVolumeMap(
            basisSpan = 6,
            pressCount = 6,
            pressPositions = listOf(0, 3, 6),
            offsets = listOf(0, 1, 6),
        )
        expectIllegalArgument { original.insertControlPointAtSegment(-1) }
        expectIllegalArgument {
            original.insertControlPointAtSegment(original.controlSegmentCount)
        }
        expectIllegalArgument { horizontallyDense.insertControlPointAtSegment(0) }
        expectIllegalArgument { verticallyDense.insertControlPointAtSegment(0) }
    }

    @Test
    fun `selected interior point removal deletes only that point and fixes endpoints`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 10,
            pressPositions = listOf(0, 2, 4, 9, 10),
            offsets = listOf(0, 2, 4, 18, 20),
        )

        val removed = original.removeControlPointAt(1)

        assertEquals(listOf(0, 4, 9, 10), removed.pressPositions)
        assertEquals(listOf(0, 4, 18, 20), removed.offsets)
        assertSame(original, original.removeControlPointAt(0))
        assertSame(original, original.removeControlPointAt(original.controlSegmentCount))
        expectIllegalArgument { original.removeControlPointAt(-1) }
        expectIllegalArgument { original.removeControlPointAt(original.controlPointCount) }
    }

    @Suppress("DEPRECATION")
    fun `map validates basis endpoints count and strict offsets`() {
        expectIllegalArgument { StepVolumeMap(0, listOf(0, 0)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(0)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(1, 4)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(0, 3)) }
        expectIllegalArgument { StepVolumeMap(4, listOf(0, 2, 2, 4)) }
        expectIllegalArgument { StepVolumeMap(2, listOf(0, 1, 2, 3)) }
        expectIllegalArgument { StepVolumeMap(4, pressCount = 0, offsets = listOf(0, 4)) }
        expectIllegalArgument { StepVolumeMap(4, pressCount = 5, offsets = listOf(0, 4)) }
        expectIllegalArgument {
            StepVolumeMap(4, 2, pressPositions = listOf(0), offsets = listOf(0, 4))
        }
        expectIllegalArgument {
            StepVolumeMap(4, 2, pressPositions = listOf(1, 2), offsets = listOf(0, 4))
        }
        expectIllegalArgument {
            StepVolumeMap(4, 2, pressPositions = listOf(0, 1), offsets = listOf(0, 4))
        }
        expectIllegalArgument {
            StepVolumeMap(
                4,
                2,
                pressPositions = listOf(0, 1, 1),
                offsets = listOf(0, 1, 4),
            )
        }
        expectIllegalArgument {
            StepVolumeMap(
                4,
                2,
                pressPositions = listOf(0, 1, 2, 2),
                offsets = listOf(0, 1, 2, 4),
            )
        }
        expectIllegalArgument {
            StepVolumeMap(
                4,
                2,
                normalizedXs = listOf(0.0, Double.NaN, 1.0),
                offsets = listOf(0, 1, 4),
            )
        }
    }

    fun `legacy constructors keep uniform x and offset evaluation interpolates`() {
        val map = StepVolumeMap(basisSpan = 20, offsets = listOf(0, 1, 4, 12, 20))

        assertEquals(4, map.pressCount)
        assertEquals(listOf(0, 1, 2, 3, 4), map.pressPositions)
        assertEquals(2, map.pressPositionAt(2))
        assertEquals(0.0, map.normalizedXAt(0), TOLERANCE)
        assertEquals(0.5, map.normalizedXAt(2), TOLERANCE)
        assertEquals(1.0, map.normalizedXAt(4), TOLERANCE)
        assertEquals(2.5, map.evaluateOffset(0.375), TOLERANCE)
        assertEquals(0.0, map.evaluateOffset(-1.0), TOLERANCE)
        assertEquals(20.0, map.evaluateOffset(2.0), TOLERANCE)
        expectIllegalArgument { map.pressPositionAt(5) }
        expectIllegalArgument { map.normalizedXAt(5) }
        expectIllegalArgument { map.evaluateOffset(Double.NaN) }
    }

    fun `integer press coordinates drive piecewise evaluation and nearest point lookup`() {
        val map = StepVolumeMap(
            basisSpan = 20,
            pressCount = 4,
            pressPositions = listOf(0, 1, 2, 4),
            offsets = listOf(0, 2, 6, 20),
        )

        assertEquals(listOf(0.0, 0.25, 0.5, 1.0), map.normalizedXs)
        assertEquals(4.0, map.evaluateOffset(0.375), TOLERANCE)
        assertEquals(13.0, map.evaluateOffset(0.75), TOLERANCE)
        assertEquals(1, map.closestControlPointIndex(0.2))
        assertEquals(2, map.closestControlPointIndex(0.55))
        assertEquals(0, map.closestControlPointIndex(-2.0))
        assertEquals(3, map.closestControlPointIndex(2.0))
        expectIllegalArgument { map.closestControlPointIndex(Double.NaN) }

        // K + 1 runtime samples stay on the uniform 0, 1/K, ..., 1 key grid.
        assertEquals(listOf(0, 2, 6, 13, 20), map.bind(RouteVolumeRange(0, 20)).indices)
    }

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

    fun `changing press count strictly reprojects integer x`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 9,
            pressPositions = listOf(0, 2, 6, 9),
            offsets = listOf(0, 4, 11, 20),
        )

        val changed = original.withPressCount(5)

        assertEquals(5, changed.pressCount)
        assertEquals(4, changed.controlPointCount)
        assertEquals(listOf(0, 1, 3, 5), changed.pressPositions)
        assertEquals(listOf(0.0, 0.2, 0.6, 1.0), changed.normalizedXs)
        assertStrictlyIncreasing(changed.pressPositions)
        assertEquals(original.offsets, changed.offsets)
        assertSame(changed, changed.withPressCount(5))
        expectIllegalArgument { original.withPressCount(0) }
        expectIllegalArgument { original.withPressCount(21) }
        expectIllegalArgument { original.withPressCount(2) }
    }

    fun `changing control point count keeps both axes integer and strict`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 8,
            pressPositions = listOf(0, 1, 5, 8),
            offsets = listOf(0, 3, 8, 20),
        )

        val expanded = original.resampleControlPoints(6)

        assertEquals(8, expanded.pressCount)
        assertEquals(6, expanded.controlPointCount)
        assertEquals(listOf(0, 1, 3, 5, 6, 8), expanded.pressPositions)
        assertEquals(listOf(0, 3, 5, 8, 12, 20), expanded.offsets)
        assertStrictlyIncreasing(expanded.pressPositions)
        assertStrictlyIncreasing(expanded.offsets)
        assertSame(expanded, expanded.resampleControlPoints(6))
        assertEquals(original, expanded.resampleControlPoints(4))
        val endpointsOnly = expanded.resampleControlPoints(2)
        assertEquals(listOf(0, 8), endpointsOnly.pressPositions)
        assertEquals(listOf(0.0, 1.0), endpointsOnly.normalizedXs)
        assertEquals(listOf(0, 20), endpointsOnly.offsets)

        var resized = endpointsOnly
        (2..9).forEach { pointCount ->
            resized = resized.resampleControlPoints(pointCount)
            assertEquals(pointCount, resized.controlPointCount)
            assertStrictlyIncreasing(resized.pressPositions)
            assertStrictlyIncreasing(resized.offsets)
        }
        expectIllegalArgument { original.resampleControlPoints(1) }
        expectIllegalArgument { original.resampleControlPoints(10) }
    }

    fun `adding points handles opposing horizontal and vertical gaps`() {
        val original = StepVolumeMap(
            basisSpan = 30,
            pressCount = 5,
            pressPositions = listOf(0, 2, 3, 5),
            offsets = listOf(0, 1, 29, 30),
        )

        val expanded = original.resampleControlPoints(6)

        assertEquals((0..5).toList(), expanded.pressPositions)
        assertEquals(6, expanded.controlPointCount)
        assertStrictlyIncreasing(expanded.pressPositions)
        assertStrictlyIncreasing(expanded.offsets)
    }

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

    fun `x and combined edits fix endpoints and preserve strict axes`() {
        val original = StepVolumeMap(
            basisSpan = 20,
            pressCount = 8,
            pressPositions = listOf(0, 2, 5, 7, 8),
            offsets = listOf(0, 3, 8, 14, 20),
        )

        assertSame(original, original.withPressPosition(0, 1))
        assertSame(original, original.withPressPosition(4, 7))
        assertEquals(3, original.withPressPosition(2, 3).pressPositionAt(2))
        assertEquals(6, original.withPressPosition(2, 100).pressPositionAt(2))

        val moved = original.moveControlPointPushing(
            controlPointIndex = 2,
            requestedPressPosition = 4,
            requestedOffset = 19,
        )
        assertEquals(4, moved.pressPositionAt(2))
        assertEquals(0.5, moved.normalizedXAt(2), TOLERANCE)
        assertEquals(listOf(0, 3, 18, 19, 20), moved.offsets)
        assertStrictlyIncreasing(moved.pressPositions)
        assertStrictlyIncreasing(moved.offsets)
        assertSame(original, original.moveControlPointPushing(0, 4, 10))
        expectIllegalArgument { original.withPressPosition(5, 4) }
        expectIllegalArgument { original.moveControlPointPushing(5, 4, 10) }
    }

    fun `touch edit pushes crossed neighbours while preserving the requested point`() {
        val original = StepVolumeMap(20, listOf(0, 3, 8, 14, 20))

        assertEquals(
            listOf(0, 1, 2, 3, 20),
            original.withOffsetPushing(controlPointIndex = 3, requestedOffset = -100).offsets,
        )
        assertEquals(
            listOf(0, 17, 18, 19, 20),
            original.withOffsetPushing(controlPointIndex = 1, requestedOffset = 100).offsets,
        )
        assertEquals(
            listOf(0, 3, 12, 14, 20),
            original.withOffsetPushing(controlPointIndex = 2, requestedOffset = 12).offsets,
        )
        assertSame(original, original.withOffsetPushing(controlPointIndex = 0, requestedOffset = 10))
        assertSame(original, original.withOffsetPushing(controlPointIndex = 4, requestedOffset = 10))
        expectIllegalArgument {
            original.withOffsetPushing(controlPointIndex = 5, requestedOffset = 10)
        }
    }

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

    fun `rebase preserves the already bound press targets when integer controls would drift`() {
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
        assertEquals(2, rebased.pressCount)
        assertEquals(listOf(0, 2, 3), rebased.offsets)
    }

    fun `same span binding preserves every authored offset exactly`() {
        val source = StepVolumeMap(15, listOf(0, 1, 3, 9, 15))

        val bound = source.bind(RouteVolumeRange(0, 15))

        assertEquals(source.offsets, bound.indices)
        assertEquals(source.pressCount, bound.effectivePressCount)
    }

    fun `fewer control points than press states are interpolated at every press`() {
        val source = StepVolumeMap(
            basisSpan = 20,
            pressCount = 5,
            offsets = listOf(0, 4, 20),
        )

        val bound = source.bind(RouteVolumeRange(0, 20))

        assertEquals(3, source.controlPointCount)
        assertEquals(5, bound.effectivePressCount)
        assertEquals(listOf(0, 2, 4, 9, 15, 20), bound.indices)
    }

    fun `control points cannot outnumber integer press states`() {
        expectIllegalArgument {
            StepVolumeMap(
                basisSpan = 20,
                pressCount = 3,
                pressPositions = listOf(0, 1, 2, 3, 3),
                offsets = listOf(0, 1, 4, 14, 20),
            )
        }
        expectIllegalArgument {
            StepVolumeMap(
                basisSpan = 20,
                pressCount = 3,
                offsets = listOf(0, 1, 4, 14, 20),
            )
        }
        val densest = StepVolumeMap.linear(20, pressCount = 3, controlPointCount = 4)
        assertEquals(listOf(0, 1, 2, 3), densest.pressPositions)
    }

    fun `press count participates in map identity independently of control points`() {
        val controls = listOf(0, 3, 12)

        val threePresses = StepVolumeMap(12, pressCount = 3, offsets = controls)
        val fourPresses = StepVolumeMap(12, pressCount = 4, offsets = controls)

        assertTrue(threePresses != fourPresses)
    }

    fun `zero to fifteen binding is strict and keeps fixed endpoints`() {
        val bound = StepVolumeMap.linear(150, 5).bind(RouteVolumeRange(0, 15))

        assertEquals(listOf(0, 3, 6, 9, 12, 15), bound.indices)
        assertEquals(5, bound.effectivePressCount)
        assertStrictlyIncreasing(bound.indices)
    }

    fun `zero to one hundred fifty binding preserves fifty effective presses`() {
        val bound = StepVolumeMap.linear(150, 50).bind(RouteVolumeRange(0, 150))

        assertEquals(0, bound.indices.first())
        assertEquals(150, bound.indices.last())
        assertEquals(51, bound.indices.size)
        assertEquals(50, bound.effectivePressCount)
        assertTrue(bound.indices.zipWithNext().all { (left, right) -> right - left == 3 })
    }

    fun `nonzero minimum binding offsets every projected target`() {
        val source = StepVolumeMap(10, listOf(0, 1, 2, 8, 10))

        val bound = source.bind(RouteVolumeRange(minIndex = 5, maxIndex = 9))

        assertEquals(listOf(5, 6, 7, 8, 9), bound.indices)
        assertEquals(4, bound.effectivePressCount)
    }

    fun `route with fewer intervals first reduces effective press count`() {
        val source = StepVolumeMap(
            basisSpan = 20,
            offsets = listOf(0, 1, 2, 3, 5, 8, 12, 16, 20),
        )

        val bound = source.bind(RouteVolumeRange(minIndex = 5, maxIndex = 9))

        assertEquals(4, bound.effectivePressCount)
        assertEquals(listOf(5, 6, 7, 8, 9), bound.indices)
    }

    fun `next step is strict and accepts an observed index between targets`() {
        val bound = StepVolumeMap(15, listOf(0, 3, 12, 15))
            .bind(RouteVolumeRange(0, 15))

        assertEquals(BoundVolumeStep(stepIndex = 2, index = 12), bound.nextStep(7, VolumeDirection.UP))
        assertEquals(BoundVolumeStep(stepIndex = 1, index = 3), bound.nextStep(7, VolumeDirection.DOWN))
        assertNull(bound.nextStep(15, VolumeDirection.UP))
        assertNull(bound.nextStep(0, VolumeDirection.DOWN))
        expectIllegalArgument { bound.nextStep(16, VolumeDirection.UP) }
    }

    fun `fixed volume route has no effective or next step`() {
        val bound = StepVolumeMap.linear(150, 50).bind(RouteVolumeRange(7, 7))

        assertEquals(listOf(7), bound.indices)
        assertEquals(0, bound.effectivePressCount)
        assertNull(bound.nextStep(7, VolumeDirection.UP))
        assertNull(bound.nextStep(7, VolumeDirection.DOWN))
    }

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

    private fun assertDoublesEqual(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedValue, actualValue) ->
            assertEquals(expectedValue, actualValue, TOLERANCE)
        }
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
