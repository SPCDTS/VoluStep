package dev.spcdts.volumemapper.runtime

import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.core.ActiveVolumePress
import dev.spcdts.volumemapper.core.AudioRouteDescriptor
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.KeyMappingConfig
import dev.spcdts.volumemapper.core.RouteConfidence
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
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
    fun `exact mapping position survives only a coherent idle snapshot`() {
        val idle = VolumeMappingState(position = VolumePosition.ExactStep(stepIndex = 17))
        val active = idle.copy(
            activePress = ActiveVolumePress(
                direction = VolumeDirection.UP,
                startedAtMillis = 10L,
                lastIntegratedAtMillis = 10L,
            ),
        )

        assertTrue(canKeepMappingPosition(idle, 50, 50, snapshot, snapshot))
        assertTrue(
            canKeepMappingPosition(
                idle,
                50,
                50,
                snapshot.copy(
                    range = snapshot.range.copy(
                        decibelsByIndex = List(snapshot.range.indexCount) { it.toDouble() },
                    ),
                ),
                snapshot,
            ),
        )
        assertFalse(canKeepMappingPosition(null, null, 50, snapshot, snapshot))
        assertFalse(
            canKeepMappingPosition(
                idle,
                50,
                50,
                snapshot,
                snapshot.copy(currentIndex = 49),
            ),
        )
        assertFalse(canKeepMappingPosition(idle, 60, 50, snapshot, snapshot))
        assertFalse(canKeepMappingPosition(active, 50, 50, snapshot, snapshot))

        assertTrue(isEffectivelyFixedVolume(true, RouteVolumeRange(0, 150)))
        assertTrue(isEffectivelyFixedVolume(false, RouteVolumeRange(7, 7)))
        assertFalse(isEffectivelyFixedVolume(false, RouteVolumeRange(0, 150)))

        val accepted = ControllerRuntimeState(
            isArmed = true,
            isForegroundServiceRunning = true,
            isAccessibilityConnected = true,
            isMediaContextSafe = true,
            statusMessage = localizedText(R.string.runtime_probing_volume),
        ).withAcceptedSnapshot(
            snapshot = snapshot,
            isVolumeFixed = false,
            isMediaContextSafe = true,
        )
        assertTrue(accepted.canInterceptKeys)
        assertEquals(localizedText(R.string.runtime_ready), accepted.statusMessage)
    }

    @Test
    fun `write queue gates from attempt time without collapsing FIFO targets`() {
        assertTrue(COORDINATOR_MIN_WRITE_INTERVAL_MILLIS <= COORDINATOR_TICK_INTERVAL_MILLIS)
        assertTrue(
            COORDINATOR_TICK_INTERVAL_MILLIS <=
                KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS,
        )

        val writes = CoordinatorWriteQueue<Int>(minimumIntervalMillis = 50L)

        writes.recordAttemptStarted(nowMillis = 100L)
        writes.enqueue(2)
        writes.enqueue(3)

        assertNull(writes.takeNextIfReady(nowMillis = 149L))
        assertEquals(2, writes.takeNextIfReady(nowMillis = 150L))
        writes.recordAttemptStarted(nowMillis = 150L)
        assertNull(writes.takeNextIfReady(nowMillis = 199L))
        assertEquals(3, writes.takeNextIfReady(nowMillis = 200L))
    }
}
