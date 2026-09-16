package dev.spcdts.volumemapper.runtime

sealed interface FixedVolumeRequestState {
    data object Idle : FixedVolumeRequestState

    data class Applying(
        val requestId: Long,
        val requestedIndex: Int,
    ) : FixedVolumeRequestState

    data class Applied(
        val requestId: Long,
        val requestedIndex: Int,
        val observedIndex: Int,
    ) : FixedVolumeRequestState

    data class Rejected(
        val requestId: Long,
        val requestedIndex: Int,
        val message: LocalizedText,
        val observedIndex: Int? = null,
    ) : FixedVolumeRequestState
}
