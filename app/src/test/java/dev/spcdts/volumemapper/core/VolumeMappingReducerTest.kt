package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VolumeMappingReducerTest {
    private val noAccelerationConfig = KeyMappingConfig(
        holdDelayMillis = 300L,
        holdUnitsPerSecond = 0.2,
        holdRampDurationMillis = 1_000L,
        holdMaximumMultiplier = 1.0,
        holdRampCurve = MappingCurve.linear(),
    )
    private val sparseMap = StepVolumeMap(
        basisSpan = 10,
        offsets = listOf(0, 1, 4, 10),
    ).bind(RouteVolumeRange(minIndex = 5, maxIndex = 15))
    private val sparseReducer = VolumeMappingReducer(sparseMap, noAccelerationConfig)

    @Test
    fun `initial state retains an observed index and derives its interpolated logical position`() {
        val state = sparseReducer.initialState(observedIndex = 8)

        assertEquals(VolumePosition.ObservedIndex(8), state.position)
        assertEquals(8, sparseReducer.targetIndex(state))
        // Index 8 is two thirds of the way from step 1 (index 6) to step 2 (index 9).
        assertEquals(5.0 / 9.0, sparseReducer.logicalPosition(state), TOLERANCE)
        expectIllegalArgument { sparseReducer.initialState(observedIndex = 4) }
    }

    @Test
    fun `observed index uses strict upper and lower configured steps`() {
        val observed = sparseReducer.initialState(observedIndex = 8)

        val up = sparseReducer.reduce(
            observed,
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 100L),
        )
        val down = sparseReducer.reduce(
            observed,
            VolumeMappingAction.KeyDown(VolumeDirection.DOWN, eventTimeMillis = 100L),
        )

        assertEquals(VolumePosition.ExactStep(2), up.state.position)
        assertEquals(9, up.targetIndex)
        assertTrue(up.writeRequested)
        assertEquals(VolumePosition.ExactStep(1), down.state.position)
        assertEquals(6, down.targetIndex)
        assertTrue(down.writeRequested)
    }

    @Test
    fun `exact short presses visit adjacent slots and reverse exactly`() {
        var state = sparseReducer.initialState(observedIndex = 5)

        sparseMap.indices.drop(1).forEachIndexed { offset, expectedIndex ->
            val time = offset.toLong() * 10L
            val down = sparseReducer.reduce(
                state,
                VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = time),
            )
            assertEquals(expectedIndex, down.targetIndex)
            assertEquals(VolumePosition.ExactStep(offset + 1), down.state.position)
            assertTrue(down.writeRequested)
            state = sparseReducer.reduce(
                down.state,
                VolumeMappingAction.KeyUp(VolumeDirection.UP, eventTimeMillis = time),
            ).state
        }

        assertEquals(15, sparseReducer.targetIndex(state))
        val beyondTop = sparseReducer.reduce(
            state,
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 100L),
        )
        assertEquals(15, beyondTop.targetIndex)
        assertFalse(beyondTop.writeRequested)
        state = sparseReducer.reduce(
            beyondTop.state,
            VolumeMappingAction.KeyUp(VolumeDirection.UP, eventTimeMillis = 100L),
        ).state

        sparseMap.indices.dropLast(1).asReversed().forEachIndexed { offset, expectedIndex ->
            val time = 200L + offset.toLong() * 10L
            val down = sparseReducer.reduce(
                state,
                VolumeMappingAction.KeyDown(VolumeDirection.DOWN, eventTimeMillis = time),
            )
            assertEquals(expectedIndex, down.targetIndex)
            assertTrue(down.writeRequested)
            state = sparseReducer.reduce(
                down.state,
                VolumeMappingAction.KeyUp(VolumeDirection.DOWN, eventTimeMillis = time),
            ).state
        }

        assertEquals(5, sparseReducer.targetIndex(state))
        assertEquals(VolumePosition.ExactStep(0), state.position)
    }

    @Test
    fun `repeated down is a strict no-op and cannot create a press`() {
        val idle = sparseReducer.initialState(observedIndex = 8)

        val repeated = sparseReducer.reduce(
            idle,
            VolumeMappingAction.KeyDown(
                VolumeDirection.UP,
                eventTimeMillis = 5_000L,
                repeated = true,
            ),
        )

        assertEquals(idle, repeated.state)
        assertNull(repeated.state.activePress)
        assertEquals(8, repeated.targetIndex)
        assertFalse(repeated.writeRequested)
    }

    @Test
    fun `hold retains fractional slots until one complete configured step accrues`() {
        val reducer = linearReducer(pressCount = 10, config = noAccelerationConfig)
        val down = reducer.reduce(
            reducer.initialState(0),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state

        val beforeDelay = reducer.reduce(
            down,
            VolumeMappingAction.AdvanceTime(nowMillis = 299L),
        )
        val halfSlot = reducer.reduce(
            beforeDelay.state,
            VolumeMappingAction.AdvanceTime(nowMillis = 550L),
        )
        val completeSlot = reducer.reduce(
            halfSlot.state,
            VolumeMappingAction.AdvanceTime(nowMillis = 800L),
        )

        assertEquals(1, beforeDelay.targetIndex)
        assertEquals(0.0, beforeDelay.state.heldStepRemainder, TOLERANCE)
        assertFalse(beforeDelay.writeRequested)
        assertEquals(1, halfSlot.targetIndex)
        assertEquals(0.5, halfSlot.state.heldStepRemainder, TOLERANCE)
        assertFalse(halfSlot.writeRequested)
        assertEquals(2, completeSlot.targetIndex)
        assertEquals(VolumePosition.ExactStep(2), completeSlot.state.position)
        assertEquals(0.0, completeSlot.state.heldStepRemainder, TOLERANCE)
        assertTrue(completeSlot.writeRequested)
    }

    @Test
    fun `hold integration is independent of timer cadence and lands only on table indices`() {
        val acceleratingConfig = noAccelerationConfig.copy(
            holdMaximumMultiplier = 3.0,
        )
        val reducer = linearReducer(pressCount = 10, config = acceleratingConfig)
        val down = reducer.reduce(
            reducer.initialState(0),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state

        val oneTick = reducer.reduce(
            down,
            VolumeMappingAction.AdvanceTime(nowMillis = 1_800L),
        ).state

        var manyTicks = down
        for (time in 350L..1_800L step 50) {
            manyTicks = reducer.reduce(
                manyTicks,
                VolumeMappingAction.AdvanceTime(nowMillis = time),
            ).state
        }

        assertEquals(oneTick.position, manyTicks.position)
        assertEquals(oneTick.heldStepRemainder, manyTicks.heldStepRemainder, TOLERANCE)
        assertEquals(8, reducer.targetIndex(oneTick))
        assertTrue(reducer.targetIndex(oneTick) in reducer.stepMap.indices)
    }

    @Test
    fun `matching key up integrates the final interval then clears gesture remainder`() {
        val reducer = linearReducer(pressCount = 10, config = noAccelerationConfig)
        val down = reducer.reduce(
            reducer.initialState(5),
            VolumeMappingAction.KeyDown(VolumeDirection.DOWN, eventTimeMillis = 0L),
        ).state

        val up = reducer.reduce(
            down,
            VolumeMappingAction.KeyUp(VolumeDirection.DOWN, eventTimeMillis = 800L),
        )
        val later = reducer.reduce(
            up.state,
            VolumeMappingAction.AdvanceTime(nowMillis = 10_000L),
        )

        assertEquals(3, up.targetIndex)
        assertNull(up.state.activePress)
        assertEquals(0.0, up.state.heldStepRemainder, TOLERANCE)
        assertTrue(up.writeRequested)
        assertEquals(up.state, later.state)
        assertFalse(later.writeRequested)
    }

    @Test
    fun `unrelated key up leaves an active press untouched`() {
        val down = sparseReducer.reduce(
            sparseReducer.initialState(8),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state

        val unrelated = sparseReducer.reduce(
            down,
            VolumeMappingAction.KeyUp(VolumeDirection.DOWN, eventTimeMillis = 1_000L),
        )

        assertEquals(down, unrelated.state)
        assertFalse(unrelated.writeRequested)
    }

    @Test
    fun `matching readback preserves exact slot while mismatches reanchor without writing`() {
        val reducer = linearReducer(pressCount = 10, config = noAccelerationConfig)
        val down = reducer.reduce(
            reducer.initialState(0),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state
        val withRemainder = reducer.reduce(
            down,
            VolumeMappingAction.AdvanceTime(nowMillis = 550L),
        ).state

        val matching = reducer.reduce(
            withRemainder,
            VolumeMappingAction.SynchronizeObserved(observedIndex = 1, forceWhilePressed = true),
        )
        val ignoredMismatch = reducer.reduce(
            withRemainder,
            VolumeMappingAction.SynchronizeObserved(observedIndex = 8),
        )
        val forcedMismatch = reducer.reduce(
            withRemainder,
            VolumeMappingAction.SynchronizeObserved(
                observedIndex = 8,
                forceWhilePressed = true,
            ),
        )

        assertEquals(withRemainder, matching.state)
        assertFalse(matching.writeRequested)
        assertEquals(withRemainder, ignoredMismatch.state)
        assertFalse(ignoredMismatch.writeRequested)
        assertEquals(VolumePosition.ObservedIndex(8), forcedMismatch.state.position)
        assertEquals(8, forcedMismatch.targetIndex)
        assertEquals(0.0, forcedMismatch.state.heldStepRemainder, TOLERANCE)
        assertEquals(VolumeDirection.UP, forcedMismatch.state.activePress?.direction)
        assertFalse(forcedMismatch.writeRequested)
    }

    @Test
    fun `fixed volume route keeps its sole index and never requests writes`() {
        val fixedMap = StepVolumeMap.linear(basisSpan = 10, pressCount = 5)
            .bind(RouteVolumeRange(minIndex = 7, maxIndex = 7))
        val reducer = VolumeMappingReducer(fixedMap, noAccelerationConfig)
        val initial = reducer.initialState(7)

        val down = reducer.reduce(
            initial,
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        )
        val held = reducer.reduce(
            down.state,
            VolumeMappingAction.AdvanceTime(nowMillis = 10_000L),
        )
        val released = reducer.reduce(
            held.state,
            VolumeMappingAction.KeyUp(VolumeDirection.UP, eventTimeMillis = 10_000L),
        )

        assertEquals(0, fixedMap.effectivePressCount)
        assertEquals(7, down.targetIndex)
        assertEquals(7, held.targetIndex)
        assertEquals(7, released.targetIndex)
        assertEquals(0.0, reducer.logicalPosition(released.state), TOLERANCE)
        assertFalse(down.writeRequested)
        assertFalse(held.writeRequested)
        assertFalse(released.writeRequested)
    }

    @Test
    fun `cancel ends press and discards fractional hold remainder without changing target`() {
        val reducer = linearReducer(pressCount = 10, config = noAccelerationConfig)
        val down = reducer.reduce(
            reducer.initialState(0),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state
        val withRemainder = reducer.reduce(
            down,
            VolumeMappingAction.AdvanceTime(nowMillis = 550L),
        ).state

        val cancelled = reducer.reduce(withRemainder, VolumeMappingAction.CancelPress)

        assertNull(cancelled.state.activePress)
        assertEquals(0.0, cancelled.state.heldStepRemainder, TOLERANCE)
        assertEquals(1, cancelled.targetIndex)
        assertFalse(cancelled.writeRequested)
    }

    private fun linearReducer(
        pressCount: Int,
        config: KeyMappingConfig,
    ): VolumeMappingReducer {
        val map = StepVolumeMap.linear(
            basisSpan = pressCount,
            pressCount = pressCount,
            controlPointCount = 2,
        )
            .bind(RouteVolumeRange(minIndex = 0, maxIndex = pressCount))
        return VolumeMappingReducer(map, config)
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
