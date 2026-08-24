package dev.spcdts.volumemapper.runtime

import dev.spcdts.volumemapper.core.ActiveVolumePress
import dev.spcdts.volumemapper.core.AudioRouteDescriptor
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.KeyMappingConfig
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.RouteConfidence
import dev.spcdts.volumemapper.core.VolumeDirection
import dev.spcdts.volumemapper.core.VolumeMappingState
import dev.spcdts.volumemapper.core.VolumePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingCoordinatorStateTest {
    private val snapshot = RouteVolumeSnapshot(
        route = AudioRouteDescriptor(
            stableId = "bluetooth:7",
            type = AudioRouteType.BLUETOOTH_A2DP,
            confidence = RouteConfidence.CONFIRMED,
        ),
        range = RouteVolumeRange(minIndex = 0, maxIndex = 150),
        currentIndex = 50,
        observedAtElapsedMillis = 1L,
    )

    @Test
    fun `mapping position continuity scenarios`() {
        `matching snapshot cannot retain an exact position without mapping state`()
        `idle state retains its exact slot only while platform state is unchanged`()
        `active state never crosses a new gesture boundary`()
        `state target must match observed index before exact position is retained`()
    }

    @Test
    fun `coordinator timing and snapshot acceptance scenarios`() {
        `minimum hold interval cannot outrun the coordinator write gate`()
        `write queue uses attempt time and preserves adjacent pending targets`()
        `single index range is effectively fixed even when backend flag is false`()
        `accepted snapshot publishes ready status from resulting state`()
    }

    fun `minimum hold interval cannot outrun the coordinator write gate`() {
        assertTrue(
            COORDINATOR_MIN_WRITE_INTERVAL_MILLIS <= COORDINATOR_TICK_INTERVAL_MILLIS,
        )
        assertTrue(
            COORDINATOR_TICK_INTERVAL_MILLIS <=
                KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS,
        )
    }

    fun `write queue uses attempt time and preserves adjacent pending targets`() {
        val writes = CoordinatorWriteQueue<Int>(minimumIntervalMillis = 50L)

        writes.recordAttemptStarted(nowMillis = 100L)
        // Simulate a Binder call that returns at 140 ms. Completion does not move the write gate,
        // so the adjacent 150 ms ticker slot remains eligible.
        writes.enqueue(2)
        assertEquals(2, writes.takeNextIfReady(nowMillis = 150L))

        // A one-millisecond actor phase offset must retain both adjacent targets in FIFO order.
        writes.recordAttemptStarted(nowMillis = 151L)
        writes.enqueue(3)
        writes.enqueue(4)
        assertNull(writes.takeNextIfReady(nowMillis = 200L))
        assertEquals(3, writes.takeNextIfReady(nowMillis = 201L))
        writes.recordAttemptStarted(nowMillis = 201L)
        assertNull(writes.takeNextIfReady(nowMillis = 250L))
        assertEquals(4, writes.takeNextIfReady(nowMillis = 251L))
    }

    fun `matching snapshot cannot retain an exact position without mapping state`() {
        assertFalse(
            canKeepMappingPosition(
                mappingState = null,
                mappingTargetIndex = null,
                previousExpectedIndex = 50,
                previousSnapshot = snapshot,
                observedSnapshot = snapshot,
            ),
        )
    }

    fun `idle state retains its exact slot only while platform state is unchanged`() {
        val idle = VolumeMappingState(position = VolumePosition.ExactStep(stepIndex = 17))
        val sameBoundsWithDiagnosticDb = snapshot.copy(
            range = snapshot.range.copy(
                decibelsByIndex = List(snapshot.range.indexCount) { it.toDouble() },
            ),
        )

        assertTrue(
            canKeepMappingPosition(idle, 50, 50, snapshot, snapshot),
        )
        assertTrue(
            canKeepMappingPosition(idle, 50, 50, sameBoundsWithDiagnosticDb, snapshot),
        )
        assertFalse(
            canKeepMappingPosition(
                idle,
                50,
                50,
                snapshot,
                snapshot.copy(currentIndex = 49),
            ),
        )
    }

    fun `active state never crosses a new gesture boundary`() {
        val active = VolumeMappingState(
            position = VolumePosition.ExactStep(stepIndex = 17),
            activePress = ActiveVolumePress(
                direction = VolumeDirection.UP,
                startedAtMillis = 10L,
                lastIntegratedAtMillis = 10L,
            ),
        )

        assertFalse(canKeepMappingPosition(active, 50, 50, snapshot, snapshot))
    }

    fun `state target must match observed index before exact position is retained`() {
        val stale = VolumeMappingState(position = VolumePosition.ExactStep(stepIndex = 18))

        assertFalse(
            canKeepMappingPosition(
                mappingState = stale,
                mappingTargetIndex = 60,
                previousExpectedIndex = 50,
                previousSnapshot = snapshot,
                observedSnapshot = snapshot,
            ),
        )
    }

    fun `single index range is effectively fixed even when backend flag is false`() {
        assertTrue(isEffectivelyFixedVolume(true, RouteVolumeRange(0, 150)))
        assertTrue(isEffectivelyFixedVolume(false, RouteVolumeRange(7, 7)))
        assertFalse(isEffectivelyFixedVolume(false, RouteVolumeRange(0, 150)))
    }

    fun `accepted snapshot publishes ready status from resulting state`() {
        val probing = ControllerRuntimeState(
            isArmed = true,
            isForegroundServiceRunning = true,
            isAccessibilityConnected = true,
            isMediaContextSafe = true,
            statusMessage = "正在探测媒体音量能力",
        )

        val accepted = probing.withAcceptedSnapshot(
            snapshot = snapshot,
            isVolumeFixed = false,
            isMediaContextSafe = true,
        )

        assertTrue(accepted.canInterceptKeys)
        assertEquals("映射服务已就绪", accepted.statusMessage)
    }
}
