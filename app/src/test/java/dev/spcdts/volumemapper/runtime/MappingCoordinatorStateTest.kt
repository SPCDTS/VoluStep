package dev.spcdts.volumemapper.runtime

import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.core.ActiveVolumePress
import dev.spcdts.volumemapper.core.AudioRouteDescriptor
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.RouteConfidence
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.VolumeDirection
import dev.spcdts.volumemapper.core.VolumeMappingState
import dev.spcdts.volumemapper.core.VolumePosition
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
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
    fun `command mailbox prioritizes controls and retains only the latest tick`() = runBlocking {
        val controls = Channel<Int>(capacity = 4)
        val ticks = Channel<Int>(Channel.CONFLATED)
        (1..100).forEach { ticks.trySend(it) }
        controls.trySend(7)

        val control = receiveNextCoordinatorMessage(controls, ticks)
        val latestTick = receiveNextCoordinatorMessage(controls, ticks)

        assertEquals(CoordinatorMailboxMessage.Control(7), control)
        assertEquals(CoordinatorMailboxMessage.LatestTick(100), latestTick)
    }

    @Test
    fun `final fixed volume snapshot failure retries after a raced transition`() {
        assertEquals(
            FixedVolumeSnapshotFailureDisposition.WAIT_FOR_FINAL,
            fixedVolumeSnapshotFailureDisposition(isFinal = false, isStampCurrent = false),
        )
        assertEquals(
            FixedVolumeSnapshotFailureDisposition.REJECT,
            fixedVolumeSnapshotFailureDisposition(isFinal = true, isStampCurrent = true),
        )
        assertEquals(
            FixedVolumeSnapshotFailureDisposition.RETRY_AFTER_TRANSITION,
            fixedVolumeSnapshotFailureDisposition(isFinal = true, isStampCurrent = false),
        )
    }

}
