package dev.spcdts.volumemapper.core

import kotlin.math.floor
import kotlin.math.max

enum class VolumeDirection(val sign: Double) {
    DOWN(-1.0),
    UP(1.0),
}

/** User-tunable key behaviour. A hold advances by one configured slot per fixed interval. */
data class KeyMappingConfig(
    val holdDelayMillis: Long = 350L,
    val holdStepIntervalMillis: Long = DEFAULT_HOLD_STEP_INTERVAL_MILLIS,
) {
    init {
        require(holdDelayMillis >= 0L) { "holdDelayMillis cannot be negative" }
        require(holdStepIntervalMillis in MIN_HOLD_STEP_INTERVAL_MILLIS..MAX_HOLD_STEP_INTERVAL_MILLIS) {
            "holdStepIntervalMillis must be in " +
                "$MIN_HOLD_STEP_INTERVAL_MILLIS..$MAX_HOLD_STEP_INTERVAL_MILLIS"
        }
        require(holdStepIntervalMillis % HOLD_STEP_INTERVAL_GRID_MILLIS == 0L) {
            "holdStepIntervalMillis must align to the ${HOLD_STEP_INTERVAL_GRID_MILLIS} ms grid"
        }
    }

    companion object {
        const val DEFAULT_HOLD_STEP_INTERVAL_MILLIS = 120L
        const val MIN_HOLD_STEP_INTERVAL_MILLIS = 60L
        const val MAX_HOLD_STEP_INTERVAL_MILLIS = 500L
        const val HOLD_STEP_INTERVAL_GRID_MILLIS = 20L
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

/**
 * Identifies whether the reducer owns an exact configured slot or is anchored only to an index
 * observed from Android. An observed index deliberately remains unsnapped until the next key
 * direction selects a strict upper or lower configured step.
 */
sealed interface VolumePosition {
    data class ExactStep(val stepIndex: Int) : VolumePosition {
        init {
            require(stepIndex >= 0) { "stepIndex cannot be negative" }
        }
    }

    data class ObservedIndex(val index: Int) : VolumePosition
}

/**
 * Reducer state for one route-bound step map.
 *
 * [heldStepRemainder] retains hold displacement smaller than one configured slot. It never affects
 * the target index directly and is cleared when the gesture ends.
 */
data class VolumeMappingState(
    val position: VolumePosition,
    val activePress: ActiveVolumePress? = null,
    val heldStepRemainder: Double = 0.0,
    val revision: Long = 0L,
) {
    init {
        require(
            heldStepRemainder.isFinite() &&
                heldStepRemainder >= 0.0 &&
                heldStepRemainder < 1.0,
        ) { "heldStepRemainder must be finite and in 0..<1" }
        require(activePress != null || heldStepRemainder == 0.0) {
            "An idle state cannot retain a held-step remainder"
        }
    }

    /** Derives the normalized editor position by inverting the route-bound piecewise-linear map. */
    fun logicalPosition(stepMap: BoundStepVolumeMap): Double {
        val current = position
        when (current) {
            is VolumePosition.ExactStep -> require(current.stepIndex in stepMap.indices.indices) {
                "Exact step is outside the bound map"
            }
            is VolumePosition.ObservedIndex -> require(stepMap.range.contains(current.index)) {
                "Observed index is outside the bound route range"
            }
        }

        val pressCount = stepMap.effectivePressCount
        if (pressCount == 0) return 0.0

        return when (current) {
            is VolumePosition.ExactStep -> {
                current.stepIndex.toDouble() / pressCount.toDouble()
            }

            is VolumePosition.ObservedIndex -> {
                val searchResult = stepMap.indices.binarySearch(current.index)
                if (searchResult >= 0) {
                    searchResult.toDouble() / pressCount.toDouble()
                } else {
                    val rightStep = -searchResult - 1
                    when {
                        rightStep <= 0 -> 0.0
                        rightStep >= stepMap.indices.size -> 1.0
                        else -> {
                            val leftStep = rightStep - 1
                            val leftIndex = stepMap.indices[leftStep]
                            val rightIndex = stepMap.indices[rightStep]
                            val fraction = (current.index - leftIndex).toDouble() /
                                (rightIndex - leftIndex).toDouble()
                            (leftStep.toDouble() + fraction) / pressCount.toDouble()
                        }
                    }
                }
            }
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
        val observedIndex: Int,
        val forceWhilePressed: Boolean = false,
    ) : VolumeMappingAction

    data object CancelPress : VolumeMappingAction
}

/**
 * A transition always reports one actual index from the current route or the untouched observed
 * index. [writeRequested] is true only when a key action changed that integer target.
 */
data class MappingReduction(
    val state: VolumeMappingState,
    val targetIndex: Int,
    val writeRequested: Boolean,
)

/** Pure state machine for discrete short presses and frame-rate-independent hold movement. */
class VolumeMappingReducer(
    val stepMap: BoundStepVolumeMap,
    val config: KeyMappingConfig,
) {
    fun initialState(observedIndex: Int): VolumeMappingState {
        require(stepMap.range.contains(observedIndex)) {
            "observedIndex is outside the bound route range"
        }
        return VolumeMappingState(position = VolumePosition.ObservedIndex(observedIndex))
    }

    fun targetIndex(state: VolumeMappingState): Int = when (val current = state.position) {
        is VolumePosition.ExactStep -> {
            require(current.stepIndex in stepMap.indices.indices) {
                "Exact step is outside the bound map"
            }
            stepMap.indices[current.stepIndex]
        }

        is VolumePosition.ObservedIndex -> {
            require(stepMap.range.contains(current.index)) {
                "Observed index is outside the bound route range"
            }
            current.index
        }
    }

    fun logicalPosition(state: VolumeMappingState): Double = state.logicalPosition(stepMap)

    fun reduce(state: VolumeMappingState, action: VolumeMappingAction): MappingReduction {
        val previousTarget = targetIndex(state)
        val nextState: VolumeMappingState
        val writeRequested: Boolean

        when (action) {
            is VolumeMappingAction.KeyDown -> {
                if (action.repeated) {
                    // Repeat cadence is only an input-liveness signal. An orphan repeat must not
                    // create a press, and movement remains owned by the monotonic hold clock.
                    nextState = state
                    writeRequested = false
                } else {
                    val advanced = advance(state, action.eventTimeMillis)
                    val moved = moveByWholeSteps(advanced, action.direction, stepCount = 1)
                    nextState = moved.copy(
                        activePress = ActiveVolumePress(
                            direction = action.direction,
                            startedAtMillis = action.eventTimeMillis,
                            lastIntegratedAtMillis = action.eventTimeMillis,
                        ),
                        heldStepRemainder = 0.0,
                    )
                    writeRequested = targetIndex(nextState) != previousTarget
                }
            }

            is VolumeMappingAction.KeyUp -> {
                val active = state.activePress
                if (active?.direction == action.direction) {
                    val advanced = advance(state, action.eventTimeMillis)
                    nextState = advanced.copy(activePress = null, heldStepRemainder = 0.0)
                    writeRequested = targetIndex(nextState) != previousTarget
                } else {
                    nextState = state
                    writeRequested = false
                }
            }

            is VolumeMappingAction.AdvanceTime -> {
                nextState = advance(state, action.nowMillis)
                writeRequested = targetIndex(nextState) != previousTarget
            }

            is VolumeMappingAction.SynchronizeObserved -> {
                require(stepMap.range.contains(action.observedIndex)) {
                    "observedIndex is outside the bound route range"
                }
                nextState = when {
                    state.activePress != null && !action.forceWhilePressed -> state
                    action.observedIndex == previousTarget -> state
                    else -> setPosition(
                        state.copy(heldStepRemainder = 0.0),
                        VolumePosition.ObservedIndex(action.observedIndex),
                    )
                }
                // Synchronizing platform readback must never echo another platform write.
                writeRequested = false
            }

            VolumeMappingAction.CancelPress -> {
                nextState = state.copy(activePress = null, heldStepRemainder = 0.0)
                writeRequested = false
            }
        }

        return MappingReduction(
            state = nextState,
            targetIndex = targetIndex(nextState),
            writeRequested = writeRequested,
        )
    }

    private fun advance(state: VolumeMappingState, requestedNowMillis: Long): VolumeMappingState {
        val press = state.activePress ?: return state
        val nowMillis = max(requestedNowMillis, press.lastIntegratedAtMillis)
        val holdStartMillis = saturatedAdd(press.startedAtMillis, config.holdDelayMillis)
        val integrationStartMillis = max(press.lastIntegratedAtMillis, holdStartMillis)
        val stepDisplacement = if (nowMillis > integrationStartMillis) {
            (nowMillis - integrationStartMillis).toDouble() /
                config.holdStepIntervalMillis.toDouble()
        } else {
            0.0
        }

        val newPress = press.copy(lastIntegratedAtMillis = nowMillis)
        if (stepDisplacement == 0.0 || stepMap.effectivePressCount == 0) {
            return state.copy(activePress = newPress)
        }

        val accumulated = state.heldStepRemainder + stepDisplacement
        val wholeSteps = when {
            !accumulated.isFinite() -> stepMap.effectivePressCount
            accumulated >= stepMap.effectivePressCount.toDouble() -> stepMap.effectivePressCount
            else -> floor(accumulated + STEP_EPSILON).toInt()
        }
        val remainder = when {
            !accumulated.isFinite() || wholeSteps == stepMap.effectivePressCount -> 0.0
            else -> (accumulated - wholeSteps.toDouble()).coerceIn(0.0, MAX_STEP_REMAINDER)
        }
        val moved = moveByWholeSteps(state, press.direction, wholeSteps)
        return moved.copy(
            activePress = newPress,
            heldStepRemainder = remainder,
        )
    }

    private fun moveByWholeSteps(
        state: VolumeMappingState,
        direction: VolumeDirection,
        stepCount: Int,
    ): VolumeMappingState {
        require(stepCount >= 0) { "stepCount cannot be negative" }
        if (stepCount == 0 || stepMap.effectivePressCount == 0) return state

        val targetStep = when (val current = state.position) {
            is VolumePosition.ExactStep -> {
                val signedTarget = when (direction) {
                    VolumeDirection.UP -> current.stepIndex.toLong() + stepCount.toLong()
                    VolumeDirection.DOWN -> current.stepIndex.toLong() - stepCount.toLong()
                }
                signedTarget.coerceIn(0L, stepMap.effectivePressCount.toLong()).toInt()
            }

            is VolumePosition.ObservedIndex -> {
                val firstStep = stepMap.nextStep(current.index, direction) ?: return state
                val remaining = stepCount - 1
                when (direction) {
                    VolumeDirection.UP -> firstStep.stepIndex + remaining
                    VolumeDirection.DOWN -> firstStep.stepIndex - remaining
                }.coerceIn(0, stepMap.effectivePressCount)
            }
        }

        return setPosition(state, VolumePosition.ExactStep(targetStep))
    }

    private fun setPosition(
        state: VolumeMappingState,
        position: VolumePosition,
    ): VolumeMappingState {
        if (position == state.position) return state
        return state.copy(position = position, revision = state.revision + 1L)
    }

    private fun saturatedAdd(left: Long, nonNegativeRight: Long): Long =
        if (left > Long.MAX_VALUE - nonNegativeRight) Long.MAX_VALUE else left + nonNegativeRight

    private companion object {
        const val STEP_EPSILON: Double = 1e-9
        const val MAX_STEP_REMAINDER: Double = 1.0 - STEP_EPSILON
    }
}
