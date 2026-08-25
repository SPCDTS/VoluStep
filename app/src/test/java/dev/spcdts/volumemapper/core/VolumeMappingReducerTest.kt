package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMappingReducerTest {
    private val fixedIntervalConfig = KeyMappingConfig(
        holdDelayMillis = 300L,
        holdStepIntervalMillis = 500L,
    )
    private val sparseMap = StepVolumeMap(
        basisSpan = 10,
        offsets = listOf(0, 1, 4, 10),
    ).bind(RouteVolumeRange(minIndex = 5, maxIndex = 15))
    private val sparseReducer = VolumeMappingReducer(sparseMap, fixedIntervalConfig)

    @Test
    fun `observed index moves to the strict neighbouring configured step`() {
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
    fun `hold integration is cadence independent and lands on configured indices`() {
        val reducer = linearReducer(pressCount = 10)
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
        assertEquals(4, reducer.targetIndex(oneTick))
        assertTrue(reducer.targetIndex(oneTick) in reducer.stepMap.indices)
    }

    @Test
    fun `matching key up integrates the final hold interval and clears the gesture`() {
        val reducer = linearReducer(pressCount = 10)
        val down = reducer.reduce(
            reducer.initialState(5),
            VolumeMappingAction.KeyDown(VolumeDirection.DOWN, eventTimeMillis = 0L),
        ).state

        val unrelated = reducer.reduce(
            down,
            VolumeMappingAction.KeyUp(VolumeDirection.UP, eventTimeMillis = 800L),
        )
        assertEquals(down, unrelated.state)
        assertFalse(unrelated.writeRequested)

        val up = reducer.reduce(
            down,
            VolumeMappingAction.KeyUp(VolumeDirection.DOWN, eventTimeMillis = 800L),
        )

        assertEquals(3, up.targetIndex)
        assertNull(up.state.activePress)
        assertEquals(0.0, up.state.heldStepRemainder, TOLERANCE)
        assertTrue(up.writeRequested)
    }

    @Test
    fun `observed synchronization and cancellation reanchor without writes`() {
        val reducer = linearReducer(pressCount = 10)
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
        val cancelled = reducer.reduce(withRemainder, VolumeMappingAction.CancelPress)

        assertEquals(withRemainder, matching.state)
        assertEquals(withRemainder, ignoredMismatch.state)
        assertEquals(VolumePosition.ObservedIndex(8), forcedMismatch.state.position)
        assertEquals(8, forcedMismatch.targetIndex)
        assertEquals(0.0, forcedMismatch.state.heldStepRemainder, TOLERANCE)
        assertEquals(VolumeDirection.UP, forcedMismatch.state.activePress?.direction)
        assertFalse(matching.writeRequested)
        assertFalse(ignoredMismatch.writeRequested)
        assertFalse(forcedMismatch.writeRequested)

        assertNull(cancelled.state.activePress)
        assertEquals(0.0, cancelled.state.heldStepRemainder, TOLERANCE)
        assertEquals(1, cancelled.targetIndex)
        assertFalse(cancelled.writeRequested)
    }

    @Test
    fun `fixed volume route never requests a platform write`() {
        val fixedMap = StepVolumeMap.linear(basisSpan = 10, pressCount = 5)
            .bind(RouteVolumeRange(minIndex = 7, maxIndex = 7))
        val reducer = VolumeMappingReducer(fixedMap, fixedIntervalConfig)
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

        assertEquals(7, down.targetIndex)
        assertEquals(7, held.targetIndex)
        assertEquals(7, released.targetIndex)
        assertFalse(down.writeRequested)
        assertFalse(held.writeRequested)
        assertFalse(released.writeRequested)
    }

    private fun linearReducer(pressCount: Int): VolumeMappingReducer {
        val map = StepVolumeMap.linear(
            basisSpan = pressCount,
            pressCount = pressCount,
            controlPointCount = 2,
        ).bind(RouteVolumeRange(minIndex = 0, maxIndex = pressCount))
        return VolumeMappingReducer(map, fixedIntervalConfig)
    }

    private companion object {
        const val TOLERANCE = 1e-8
    }
}
