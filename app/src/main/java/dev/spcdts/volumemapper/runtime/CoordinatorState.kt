package dev.spcdts.volumemapper.runtime

import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.VolumeMappingState

data class ControllerRuntimeState(
    val isArmed: Boolean = false,
    val isForegroundServiceRunning: Boolean = false,
    val isAccessibilityConnected: Boolean = false,
    val isFailOpen: Boolean = false,
    val isVolumeFixed: Boolean = false,
    val isMediaContextSafe: Boolean = false,
    val snapshot: RouteVolumeSnapshot? = null,
    val logicalPosition: Double? = null,
    val expectedIndex: Int? = null,
    val consecutiveWriteFailures: Int = 0,
    val statusMessage: LocalizedText = localizedText(R.string.runtime_mapping_disabled),
    val lastUpdatedAtMillis: Long = 0L,
) {
    val canInterceptKeys: Boolean
        get() = isArmed &&
            isForegroundServiceRunning &&
            isAccessibilityConnected &&
            !isFailOpen &&
            !isVolumeFixed &&
            isMediaContextSafe &&
            snapshot != null
}

/** 基于已接收快照后的完整状态决定文案，避免读取复制前的 [canInterceptKeys]。 */
internal fun ControllerRuntimeState.withAcceptedSnapshot(
    snapshot: RouteVolumeSnapshot,
    isVolumeFixed: Boolean,
    isMediaContextSafe: Boolean,
): ControllerRuntimeState {
    val acceptedState = copy(
        snapshot = snapshot,
        isVolumeFixed = isVolumeFixed,
        isMediaContextSafe = isMediaContextSafe,
        expectedIndex = snapshot.currentIndex,
    )
    return acceptedState.copy(
        statusMessage = when {
            acceptedState.isVolumeFixed -> localizedText(R.string.runtime_fixed_volume)
            !acceptedState.isMediaContextSafe -> localizedText(R.string.runtime_unsafe_media_context)
            acceptedState.isFailOpen -> acceptedState.statusMessage
            acceptedState.canInterceptKeys -> localizedText(R.string.runtime_ready)
            else -> acceptedState.statusMessage
        },
    )
}

/** 唯一标识一次物理按键手势；repeat 和 UP 必须携带与初始 DOWN 相同的 downTime。 */
internal data class KeyToken(
    val deviceId: Int,
    val keyCode: Int,
    val downTimeMillis: Long,
)

internal fun shouldProcessCoordinatorTick(owner: KeyToken?, gestureToken: KeyToken): Boolean =
    owner == gestureToken

/** Pure continuity decision kept outside the actor so route/readback edge cases are unit-testable. */
internal fun canKeepMappingPosition(
    mappingState: VolumeMappingState?,
    mappingTargetIndex: Int?,
    previousExpectedIndex: Int?,
    previousSnapshot: RouteVolumeSnapshot?,
    observedSnapshot: RouteVolumeSnapshot,
): Boolean =
    mappingState != null &&
        mappingState.activePress == null &&
        mappingTargetIndex == observedSnapshot.currentIndex &&
        previousExpectedIndex == observedSnapshot.currentIndex &&
        previousSnapshot?.route?.stableId == observedSnapshot.route.stableId &&
        previousSnapshot.range.hasSameIndexBounds(observedSnapshot.range)

internal fun isEffectivelyFixedVolume(
    backendReportsFixed: Boolean,
    range: RouteVolumeRange,
): Boolean = backendReportsFixed || range.minIndex == range.maxIndex

internal enum class FixedVolumeSnapshotFailureDisposition {
    WAIT_FOR_FINAL,
    REJECT,
    RETRY_AFTER_TRANSITION,
}

internal fun fixedVolumeSnapshotFailureDisposition(
    isFinal: Boolean,
    isStampCurrent: Boolean,
): FixedVolumeSnapshotFailureDisposition = when {
    !isFinal -> FixedVolumeSnapshotFailureDisposition.WAIT_FOR_FINAL
    isStampCurrent -> FixedVolumeSnapshotFailureDisposition.REJECT
    else -> FixedVolumeSnapshotFailureDisposition.RETRY_AFTER_TRANSITION
}

internal fun RouteVolumeRange.hasSameIndexBounds(other: RouteVolumeRange): Boolean =
    minIndex == other.minIndex && maxIndex == other.maxIndex

