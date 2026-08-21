package dev.spcdts.volumemapper.core

import kotlin.math.max

enum class VolumeDirection(val sign: Double) {
    DOWN(-1.0),
    UP(1.0),
}

/** User-tunable key behaviour. The hold ramp is integrated exactly between timer ticks. */
data class KeyMappingConfig(
    val tapStep: Double = 0.025,
    val holdDelayMillis: Long = 350L,
    val holdUnitsPerSecond: Double = 0.12,
    val holdRampDurationMillis: Long = 1_500L,
    val holdMaximumMultiplier: Double = 4.0,
    val holdRampCurve: MappingCurve = MappingCurve.linear(),
) {
    init {
        require(tapStep.isFinite() && tapStep in 0.0..1.0) {
            "tapStep must be finite and in 0..1"
        }
        require(holdDelayMillis >= 0L) { "holdDelayMillis cannot be negative" }
        require(holdUnitsPerSecond.isFinite() && holdUnitsPerSecond >= 0.0) {
            "holdUnitsPerSecond must be finite and non-negative"
        }
        require(holdRampDurationMillis > 0L) { "holdRampDurationMillis must be positive" }
        require(holdMaximumMultiplier.isFinite() && holdMaximumMultiplier >= 1.0) {
            "holdMaximumMultiplier must be finite and at least 1"
        }
    }
}

data class ActiveVolumePress(
    val direction: VolumeDirection,
    val startedAtMillis: Long,
    val lastIntegratedAtMillis: Long,
) {
    init {
        require(lastIntegratedAtMillis >= startedAtMillis) {
            "lastIntegratedAtMillis cannot precede startedAtMillis"
        }
    }
}

/** The reducer owns logical x; the selected [MappingCurve] turns it into the requested volume V. */
data class VolumeMappingState(
    val logicalPosition: Double,
    val activePress: ActiveVolumePress? = null,
    val revision: Long = 0L,
) {
    init {
        require(logicalPosition.isFinite() && logicalPosition in 0.0..1.0) {
            "logicalPosition must be finite and in 0..1"
        }
    }
}

sealed interface VolumeMappingAction {
    data class KeyDown(
        val direction: VolumeDirection,
        val eventTimeMillis: Long,
        val repeated: Boolean = false,
    ) : VolumeMappingAction

    data class KeyUp(
        val direction: VolumeDirection,
        val eventTimeMillis: Long,
    ) : VolumeMappingAction

    data class AdvanceTime(val nowMillis: Long) : VolumeMappingAction

    data class SynchronizeObserved(
        val outputVolume: Double,
        val forceWhilePressed: Boolean = false,
    ) : VolumeMappingAction

    data object CancelPress : VolumeMappingAction
}

/**
 * A transition always reports the current mapped target. [writeRequested] is true only when a key
 * action changed x and the platform backend should be asked to apply a new volume.
 */
data class MappingReduction(
    val state: VolumeMappingState,
    val targetOutputVolume: Double,
    val writeRequested: Boolean,
)

/** Pure state machine for tap, repeat and hold behaviour. */
class VolumeMappingReducer(
    val outputCurve: MappingCurve,
    val config: KeyMappingConfig,
) {
    fun initialState(observedOutputVolume: Double): VolumeMappingState =
        VolumeMappingState(
            logicalPosition = outputCurve.inverse(observedOutputVolume.coerceIn(0.0, 1.0)),
        )

    fun targetOutput(state: VolumeMappingState): Double = outputCurve.evaluate(state.logicalPosition)

    fun reduce(state: VolumeMappingState, action: VolumeMappingAction): MappingReduction {
        val nextState: VolumeMappingState
        val writeRequested: Boolean

        when (action) {
            is VolumeMappingAction.KeyDown -> {
                if (action.repeated) {
                    // Repeat cadence is an input-liveness signal, not a movement clock. In
                    // particular, an orphan repeat must never create a new press after cancellation.
                    nextState = state
                    writeRequested = false
                } else {
                    val advanced = advance(state, action.eventTimeMillis)
                    val moved = moveBy(advanced, action.direction.sign * config.tapStep)
                    nextState = moved.copy(
                        activePress = ActiveVolumePress(
                            direction = action.direction,
                            startedAtMillis = action.eventTimeMillis,
                            lastIntegratedAtMillis = action.eventTimeMillis,
                        ),
                    )
                    writeRequested = moved.logicalPosition != advanced.logicalPosition ||
                        advanced.logicalPosition != state.logicalPosition
                }
            }

            is VolumeMappingAction.KeyUp -> {
                val active = state.activePress
                if (active?.direction == action.direction) {
                    val advanced = advance(state, action.eventTimeMillis)
                    nextState = advanced.copy(activePress = null)
                    writeRequested = advanced.logicalPosition != state.logicalPosition
                } else {
                    nextState = state
                    writeRequested = false
                }
            }

            is VolumeMappingAction.AdvanceTime -> {
                nextState = advance(state, action.nowMillis)
                writeRequested = nextState.logicalPosition != state.logicalPosition
            }

            is VolumeMappingAction.SynchronizeObserved -> {
                if (state.activePress != null && !action.forceWhilePressed) {
                    nextState = state
                } else {
                    val observed = action.outputVolume.coerceIn(0.0, 1.0)
                    nextState = setPosition(state, outputCurve.inverse(observed))
                }
                // A readback synchronization must never echo another platform write.
                writeRequested = false
            }

            VolumeMappingAction.CancelPress -> {
                nextState = state.copy(activePress = null)
                writeRequested = false
            }
        }

        return MappingReduction(
            state = nextState,
            targetOutputVolume = targetOutput(nextState),
            writeRequested = writeRequested,
        )
    }

    private fun advance(state: VolumeMappingState, requestedNowMillis: Long): VolumeMappingState {
        val press = state.activePress ?: return state
        val nowMillis = max(requestedNowMillis, press.lastIntegratedAtMillis)
        val holdStartMillis = press.startedAtMillis + config.holdDelayMillis
        val integrationStartMillis = max(press.lastIntegratedAtMillis, holdStartMillis)
        val displacement = if (nowMillis > integrationStartMillis) {
            holdDisplacement(
                fromHeldMillis = integrationStartMillis - holdStartMillis,
                toHeldMillis = nowMillis - holdStartMillis,
            ) * press.direction.sign
        } else {
            0.0
        }

        val moved = moveBy(state, displacement)
        return moved.copy(
            activePress = press.copy(lastIntegratedAtMillis = nowMillis),
        )
    }

    private fun holdDisplacement(fromHeldMillis: Long, toHeldMillis: Long): Double {
        val durationSeconds = (toHeldMillis - fromHeldMillis) / MILLIS_PER_SECOND
        val rampDurationMillis = config.holdRampDurationMillis.toDouble()
        val normalizedFrom = fromHeldMillis / rampDurationMillis
        val normalizedTo = toHeldMillis / rampDurationMillis
        val rampAreaSeconds = config.holdRampCurve.integral(normalizedFrom, normalizedTo) *
            rampDurationMillis / MILLIS_PER_SECOND
        val multiplierAreaSeconds = durationSeconds +
            (config.holdMaximumMultiplier - 1.0) * rampAreaSeconds
        return config.holdUnitsPerSecond * multiplierAreaSeconds
    }

    private fun moveBy(state: VolumeMappingState, delta: Double): VolumeMappingState =
        setPosition(state, (state.logicalPosition + delta).coerceIn(0.0, 1.0))

    private fun setPosition(state: VolumeMappingState, logicalPosition: Double): VolumeMappingState {
        if (logicalPosition == state.logicalPosition) return state
        return state.copy(logicalPosition = logicalPosition, revision = state.revision + 1L)
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1_000.0
    }
}
