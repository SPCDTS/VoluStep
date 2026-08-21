package dev.spcdts.volumemapper.runtime

import dev.spcdts.volumemapper.core.ActiveVolumePress
import dev.spcdts.volumemapper.core.AudioRouteDescriptor
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.VolumeDirection
import dev.spcdts.volumemapper.core.VolumeMappingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingCoordinatorStateTest {
    private val snapshot = RouteVolumeSnapshot(
        route = AudioRouteDescriptor(
            stableId = "bluetooth:7",
            type = AudioRouteType.BLUETOOTH_A2DP,
        ),
        range = RouteVolumeRange(minIndex = 0, maxIndex = 150),
        currentIndex = 50,
        observedAtElapsedMillis = 1L,
    )

    @Test
    fun `matching snapshot cannot retain remainder without mapping state`() {
        assertFalse(
            canKeepLogicalRemainder(
                mappingState = null,
                previousExpectedIndex = 50,
                previousSnapshot = snapshot,
                observedSnapshot = snapshot,
            ),
        )
    }

    @Test
    fun `idle state retains sub-index remainder only while platform state is unchanged`() {
        val idle = VolumeMappingState(logicalPosition = 0.337)

        assertTrue(
            canKeepLogicalRemainder(idle, 50, snapshot, snapshot),
        )
        assertFalse(
            canKeepLogicalRemainder(
                idle,
                50,
                snapshot,
                snapshot.copy(currentIndex = 49),
            ),
        )
    }

    @Test
    fun `active state never crosses a new gesture boundary`() {
        val active = VolumeMappingState(
            logicalPosition = 0.337,
            activePress = ActiveVolumePress(
                direction = VolumeDirection.UP,
                startedAtMillis = 10L,
                lastIntegratedAtMillis = 10L,
            ),
        )

        assertFalse(canKeepLogicalRemainder(active, 50, snapshot, snapshot))
    }
}
