package dev.spcdts.volumemapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMappingReducerTest {
    private val config = KeyMappingConfig(
        tapStep = 0.1,
        holdDelayMillis = 300L,
        holdUnitsPerSecond = 0.2,
        holdRampDurationMillis = 1_000L,
        holdMaximumMultiplier = 3.0,
        holdRampCurve = MappingCurve.linear(),
    )
    private val reducer = VolumeMappingReducer(MappingCurve.linear(), config)

    @Test
    fun `initial state inverts the selected output curve`() {
        val curvedReducer = VolumeMappingReducer(
            MappingPreset.LOW_VOLUME_FINE.createCurve(),
            config,
        )

        val state = curvedReducer.initialState(observedOutputVolume = 0.15)

        assertEquals(0.5, state.logicalPosition, TOLERANCE)
        assertEquals(0.15, curvedReducer.targetOutput(state), TOLERANCE)
    }

    @Test
    fun `initial down applies one tap and repeated downs do not add taps`() {
        val initial = reducer.initialState(0.5)
        val down = reducer.reduce(
            initial,
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 100L),
        )
        val repeated = reducer.reduce(
            down.state,
            VolumeMappingAction.KeyDown(
                VolumeDirection.UP,
                eventTimeMillis = 200L,
                repeated = true,
            ),
        )

        assertEquals(0.6, down.state.logicalPosition, TOLERANCE)
        assertTrue(down.writeRequested)
        assertEquals(0.6, repeated.state.logicalPosition, TOLERANCE)
        assertFalse(repeated.writeRequested)
        assertEquals(VolumeDirection.UP, repeated.state.activePress?.direction)
    }

    @Test
    fun `orphan repeated down is a strict no-op and cannot restart a press`() {
        val idle = reducer.initialState(0.5)

        val repeated = reducer.reduce(
            idle,
            VolumeMappingAction.KeyDown(
                VolumeDirection.UP,
                eventTimeMillis = 5_000L,
                repeated = true,
            ),
        )

        assertEquals(idle, repeated.state)
        assertNull(repeated.state.activePress)
        assertFalse(repeated.writeRequested)
    }

    @Test
    fun `hold waits for delay then integrates acceleration`() {
        val initial = reducer.initialState(0.5)
        val down = reducer.reduce(
            initial,
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 100L),
        )
        val beforeDelay = reducer.reduce(
            down.state,
            VolumeMappingAction.AdvanceTime(nowMillis = 399L),
        )
        val afterHalfSecond = reducer.reduce(
            beforeDelay.state,
            VolumeMappingAction.AdvanceTime(nowMillis = 900L),
        )

        assertEquals(0.6, beforeDelay.state.logicalPosition, TOLERANCE)
        assertFalse(beforeDelay.writeRequested)
        // 0.5 s base area + 2 * integral(0..0.5)=0.25 s; multiplied by 0.2 units/s.
        assertEquals(0.75, afterHalfSecond.state.logicalPosition, TOLERANCE)
        assertTrue(afterHalfSecond.writeRequested)
    }

    @Test
    fun `hold integration is independent of timer cadence`() {
        val start = reducer.initialState(0.1)
        val down = reducer.reduce(
            start,
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

        assertEquals(oneTick.logicalPosition, manyTicks.logicalPosition, TOLERANCE)
        assertEquals(0.9, oneTick.logicalPosition, TOLERANCE)
    }

    @Test
    fun `matching key up integrates final interval and stops movement`() {
        val initial = reducer.initialState(0.5)
        val down = reducer.reduce(
            initial,
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

        assertEquals(0.25, up.state.logicalPosition, TOLERANCE)
        assertNull(up.state.activePress)
        assertTrue(up.writeRequested)
        assertEquals(up.state, later.state)
        assertFalse(later.writeRequested)
    }

    @Test
    fun `unrelated key up leaves active press untouched`() {
        val down = reducer.reduce(
            reducer.initialState(0.5),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state

        val unrelated = reducer.reduce(
            down,
            VolumeMappingAction.KeyUp(VolumeDirection.DOWN, eventTimeMillis = 1_000L),
        )

        assertEquals(down, unrelated.state)
        assertFalse(unrelated.writeRequested)
    }

    @Test
    fun `readback sync is ignored during press unless forced and never writes`() {
        val down = reducer.reduce(
            reducer.initialState(0.5),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state

        val ignored = reducer.reduce(
            down,
            VolumeMappingAction.SynchronizeObserved(outputVolume = 0.2),
        )
        val forced = reducer.reduce(
            down,
            VolumeMappingAction.SynchronizeObserved(
                outputVolume = 0.2,
                forceWhilePressed = true,
            ),
        )

        assertEquals(down, ignored.state)
        assertEquals(0.2, forced.state.logicalPosition, TOLERANCE)
        assertFalse(ignored.writeRequested)
        assertFalse(forced.writeRequested)
    }

    @Test
    fun `movement clamps at endpoints without redundant writes`() {
        val atTop = reducer.initialState(1.0)
        val up = reducer.reduce(
            atTop,
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        )

        assertEquals(1.0, up.state.logicalPosition, TOLERANCE)
        assertFalse(up.writeRequested)
        assertEquals(atTop.revision, up.state.revision)
    }

    @Test
    fun `cancel ends press without changing the target`() {
        val down = reducer.reduce(
            reducer.initialState(0.5),
            VolumeMappingAction.KeyDown(VolumeDirection.UP, eventTimeMillis = 0L),
        ).state

        val cancelled = reducer.reduce(down, VolumeMappingAction.CancelPress)

        assertNull(cancelled.state.activePress)
        assertEquals(down.logicalPosition, cancelled.state.logicalPosition, TOLERANCE)
        assertFalse(cancelled.writeRequested)
    }

    private companion object {
        const val TOLERANCE = 1e-8
    }
}
