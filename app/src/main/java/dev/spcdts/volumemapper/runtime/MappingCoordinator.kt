package dev.spcdts.volumemapper.runtime

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.MainThread
import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.audio.AudioManagerVolumeBackend
import dev.spcdts.volumemapper.core.BoundStepVolumeMap
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.VolumeDirection
import dev.spcdts.volumemapper.core.VolumeMappingAction
import dev.spcdts.volumemapper.core.VolumeMappingReducer
import dev.spcdts.volumemapper.core.VolumeMappingState
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

internal const val COORDINATOR_TICK_INTERVAL_MILLIS = 20L

// The fastest configured hold advances every 60 ms. A 20 ms clock aligns with that grid and limits
// scheduler jitter without increasing AudioManager writes: the reducer requests only changed slots.
internal const val COORDINATOR_MIN_WRITE_INTERVAL_MILLIS = COORDINATOR_TICK_INTERVAL_MILLIS

/**
 * Actor-owned FIFO that separates write-attempt throttling from post-write verification time.
 * A Binder call may finish well after its attempt began; using completion time for both concerns
 * shifts the gate into the next ticker slot and can make a newer target replace an older one.
 */
internal class CoordinatorWriteQueue<T>(
    private val minimumIntervalMillis: Long = COORDINATOR_MIN_WRITE_INTERVAL_MILLIS,
) {
    private val pending = ArrayDeque<T>()
    private var lastAttemptStartedAtMillis = Long.MIN_VALUE

    init {
        require(minimumIntervalMillis >= 0L) { "minimumIntervalMillis must not be negative" }
    }

    val hasPending: Boolean
        get() = pending.isNotEmpty()

    fun canStartAttempt(nowMillis: Long): Boolean =
        lastAttemptStartedAtMillis == Long.MIN_VALUE ||
            nowMillis - lastAttemptStartedAtMillis >= minimumIntervalMillis

    fun recordAttemptStarted(nowMillis: Long) {
        lastAttemptStartedAtMillis = nowMillis
    }

    fun enqueue(value: T) {
        pending.addLast(value)
    }

    fun takeNextIfReady(nowMillis: Long): T? {
        if (pending.isEmpty() || !canStartAttempt(nowMillis)) return null
        return pending.removeFirst()
    }

    fun clearPending() {
        pending.clear()
    }

    fun reset() {
        pending.clear()
        lastAttemptStartedAtMillis = Long.MIN_VALUE
    }
}

internal sealed interface CoordinatorMailboxMessage<out C, out T> {
    data class Control<C>(val value: C) : CoordinatorMailboxMessage<C, Nothing>
    data class LatestTick<T>(val value: T) : CoordinatorMailboxMessage<Nothing, T>
}

/** Gives control/release commands priority while retaining only the latest absolute-time tick. */
internal suspend fun <C : Any, T : Any> receiveNextCoordinatorMessage(
    controls: Channel<C>,
    ticks: Channel<T>,
): CoordinatorMailboxMessage<C, T> {
    controls.tryReceive().getOrNull()?.let { ready ->
        return CoordinatorMailboxMessage.Control(ready)
    }
    return select {
        controls.onReceive { CoordinatorMailboxMessage.Control(it) }
        ticks.onReceive { CoordinatorMailboxMessage.LatestTick(it) }
    }
}

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

private fun RouteVolumeRange.hasSameIndexBounds(other: RouteVolumeRange): Boolean =
    minIndex == other.minIndex && maxIndex == other.maxIndex

/**
 * 串行化按键、路由、写入和回读的 actor。
 *
 * Accessibility 回调只读取原子缓存、维护一个手势 owner，并向有界队列投递初始 DOWN/UP。
 * repeat 只刷新 heartbeat；所有 AudioManager I/O 都在 actor 中执行，并在 I/O 前后复核 epoch。
 */
class MappingCoordinator(
    private val backend: AudioManagerVolumeBackend,
    private val settingsRepository: SettingsRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + Dispatchers.Default,
    )
    private val commands = Channel<Command>(COMMAND_BUFFER_CAPACITY)
    private val ticks = Channel<Command.Tick>(Channel.CONFLATED)

    /** Callback-side state. None of these reads performs Binder I/O. */
    private val eligible = AtomicBoolean(false)
    private val desiredArmed = AtomicBoolean(false)
    private val desiredForegroundRunning = AtomicBoolean(false)
    private val desiredAccessibilityConnected = AtomicBoolean(false)
    private val startedActivityCount = AtomicInteger(0)
    private val controlEpoch = AtomicLong(0L)
    private val routeEpoch = AtomicLong(0L)
    private val gestureOwner = AtomicReference<KeyToken?>(null)
    private val ownerHeartbeatAtMillis = AtomicLong(0L)
    private val tickerJob = AtomicReference<Job?>(null)
    private val ownerDrainWatchdogJob = AtomicReference<Job?>(null)
    private val verificationJob = AtomicReference<Job?>(null)
    private val fixedVolumeVerificationJob = AtomicReference<Job?>(null)
    private val fixedVolumeRequestSequence = AtomicLong(0L)
    private val observedSettings = AtomicReference(settingsRepository.settings.value)

    private val _runtime = MutableStateFlow(ControllerRuntimeState())
    val runtime: StateFlow<ControllerRuntimeState> = _runtime
    private val _fixedVolumeRequestState = MutableStateFlow<FixedVolumeRequestState>(
        FixedVolumeRequestState.Idle,
    )
    val fixedVolumeRequestState: StateFlow<FixedVolumeRequestState> =
        _fixedVolumeRequestState

    /** Actor-side state. */
    private var actorControlEpoch = 0L
    private var settings: VolumeMapperSettings = observedSettings.get()
    private var boundStepMap: BoundStepVolumeMap = settings.outputMap.bind(RouteVolumeRange(0, 0))
    private var reducer = VolumeMappingReducer(boundStepMap, settings.keyConfig)
    private var mappingState: VolumeMappingState? = null
    private var activeGesture: ActiveGesture? = null
    private var lastEnvironmentGuardAtMillis = Long.MIN_VALUE
    private val writeQueue = CoordinatorWriteQueue<PendingWrite>()
    private var verificationCycle: VerificationCycle? = null
    private var fixedVolumeVerification: FixedVolumeVerification? = null
    private var nextVerificationId = 0L

    init {
        scope.launch { actorLoop() }
        scope.launch {
            settingsRepository.settings.collect { newSettings ->
                // Do not blindly drop the collector's first emission: DataStore may finish loading
                // between the constructor's value read and subscription. Comparing against the
                // last observed value skips only a genuinely identical initial snapshot.
                if (observedSettings.getAndSet(newSettings) == newSettings) return@collect
                val stamp = beginControlTransition()
                commands.send(Command.SettingsChanged(newSettings, stamp))
            }
        }
        scope.launch {
            backend.environmentChanges().collect {
                // The backend emits for route/topology/mode changes. Treat every event as a new
                // route epoch: conservative invalidation is safer than writing against ambiguity.
                val stamp = beginControlTransition(routeChanged = true)
                commands.send(Command.EnvironmentChanged(stamp))
            }
        }
        enqueueGuaranteed(Command.RefreshSnapshot(currentStamp()))
    }

    fun onForegroundServiceStarted() {
        desiredForegroundRunning.set(true)
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.ForegroundChanged(running = true, stamp = stamp))
    }

    fun onForegroundServiceStopped() {
        desiredForegroundRunning.set(false)
        desiredArmed.set(false)
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.ForegroundChanged(running = false, stamp = stamp))
    }

    fun setAccessibilityConnected(connected: Boolean) {
        desiredAccessibilityConnected.set(connected)
        val stamp = beginControlTransition(clearOwner = !connected)
        enqueueGuaranteed(Command.AccessibilityChanged(connected, stamp))
    }

    fun arm() {
        desiredArmed.set(true)
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.Arm(stamp))
    }

    fun disarm() {
        desiredArmed.set(false)
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.Disarm(localizedText(R.string.runtime_stopped_by_user), stamp))
    }

    fun retry() {
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.Retry(stamp))
    }

    fun refreshSnapshot() {
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.RefreshSnapshot(stamp))
    }

    fun onUiStarted() {
        startedActivityCount.incrementAndGet()
    }

    fun onUiStopped() {
        startedActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    /**
     * Applies an explicit, visible-UI media-volume request without requiring Accessibility or the
     * background controller. The actor still serializes it against physical-key writes and verifies
     * the actual system result because Android and Bluetooth routes may silently quantize or reject.
     */
    @MainThread
    fun requestFixedVolume(index: Int) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Fixed volume requests must originate from the main thread"
        }
        require(index >= 0) { "Volume index must not be negative" }
        val requestId = fixedVolumeRequestSequence.incrementAndGet()
        if (!isUiVisible()) {
            _fixedVolumeRequestState.value = FixedVolumeRequestState.Rejected(
                requestId = requestId,
                requestedIndex = index,
                message = localizedText(R.string.fixed_volume_requires_visible_app),
            )
            return
        }
        val sourceSnapshot = _runtime.value.snapshot
        if (sourceSnapshot == null) {
            _fixedVolumeRequestState.value = FixedVolumeRequestState.Rejected(
                requestId = requestId,
                requestedIndex = index,
                message = localizedText(R.string.audio_error_no_output),
            )
            return
        }
        val stamp = beginControlTransition()
        _fixedVolumeRequestState.value = FixedVolumeRequestState.Applying(
            requestId = requestId,
            requestedIndex = index,
        )
        enqueueGuaranteed(
            Command.SetFixedVolume(
                requestId = requestId,
                requestedIndex = index,
                sourceRouteId = sourceSnapshot.route.stableId,
                sourceRangeIdentity = sourceSnapshot.range.identity,
                stamp = stamp,
            ),
        )
    }

    fun acknowledgeFixedVolumeRequest(requestId: Long) {
        val state = _fixedVolumeRequestState.value
        if (state is FixedVolumeRequestState.Rejected && state.requestId == requestId) {
            _fixedVolumeRequestState.compareAndSet(state, FixedVolumeRequestState.Idle)
        }
    }

    /**
     * 返回 true 即消费完整手势。一次初始 DOWN 被消费后，即使控制状态随后失效，也会继续消费
     * 同一 [KeyToken] 的 repeat 和 UP；Accessibility 断连是唯一会立即清除 owner 的控制变化。
     */
    fun handleAccessibilityKey(event: KeyEvent): Boolean {
        val direction = event.keyCode.toDirection() ?: return false
        val token = KeyToken(event.deviceId, event.keyCode, event.downTime)

        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        val expirationDisposition = expiredKeyEventDisposition(
            isExpired = isAccessibilityKeyEventExpired(
                nowUptimeMillis = SystemClock.uptimeMillis(),
                eventTimeMillis = event.eventTime,
            ),
            isInitialDown = action == KeyEvent.ACTION_DOWN && event.repeatCount == 0,
            ownsGesture = gestureOwner.get() == token,
        )
        when (expirationDisposition) {
            ExpiredKeyEventDisposition.PASS_THROUGH -> return false
            ExpiredKeyEventDisposition.DRAIN_OWNED_GESTURE -> {
                return drainExpiredOwnedGesture(token)
            }
            ExpiredKeyEventDisposition.PROCESS -> Unit
        }

        return when (action) {
            KeyEvent.ACTION_DOWN -> handleAccessibilityDown(event, token, direction)
            KeyEvent.ACTION_UP -> handleAccessibilityUp(event, token, direction)
            else -> false
        }
    }

    private fun handleAccessibilityDown(
        event: KeyEvent,
        token: KeyToken,
        direction: VolumeDirection,
    ): Boolean {
        val owner = gestureOwner.get()
        if (owner != null) {
            if (owner != token) return false
            // A repeat is never queued and therefore can never restart a cancelled gesture.
            ownerHeartbeatAtMillis.accumulateAndGet(event.eventTime, ::maxLong)
            return true
        }

        // An orphaned repeat (for example after watchdog/disconnect) may not claim ownership.
        if (event.repeatCount > 0) return false
        if (!eligible.get() || !backend.isMediaContextSafe) return false

        val stamp = currentStamp()
        if (!gestureOwner.compareAndSet(null, token)) return false
        ownerHeartbeatAtMillis.set(event.eventTime)

        // If invalidation completed before ownership was installed, this DOWN has not yet been
        // consumed and may safely fall through to the platform.
        if (!eligible.get() || !backend.isMediaContextSafe || currentStamp() != stamp) {
            gestureOwner.compareAndSet(token, null)
            return false
        }

        val accepted = commands.trySend(
            Command.KeyDown(
                token = token,
                direction = direction,
                eventTimeMillis = event.eventTime,
                stamp = stamp,
            ),
        ).isSuccess
        if (!accepted) {
            gestureOwner.compareAndSet(token, null)
            return false
        }

        // A transition can race the enqueue. The actor will reject the stale epoch, while owner is
        // retained until UP so the system never receives only half of a consumed event stream.
        return true
    }

    private fun handleAccessibilityUp(
        event: KeyEvent,
        token: KeyToken,
        direction: VolumeDirection,
    ): Boolean {
        // AtomicReference.compareAndSet compares object identity, while every callback creates a
        // fresh KeyToken. Compare the token value first, then CAS with the exact stored instance.
        val owner = gestureOwner.get()
        if (owner != token || !gestureOwner.compareAndSet(owner, null)) return false
        cancelOwnerDrainWatchdog()
        ownerHeartbeatAtMillis.accumulateAndGet(event.eventTime, ::maxLong)

        val command = Command.KeyUp(token, direction, event.eventTime)
        if (!commands.trySend(command).isSuccess) {
            // UP is a drain event for an already-consumed gesture and therefore must not be dropped.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { commands.send(command) }
        }
        return true
    }

    /**
     * 系统已经为超时事件执行了默认行为；这里只排空此前接管的手势，绝不把旧 UP 交给 reducer。
     * 回调热路径仅做原子状态切换和协程投递，不读取 AudioManager/Binder。
     */
    private fun drainExpiredOwnedGesture(token: KeyToken): Boolean {
        val owner = gestureOwner.get()
        if (owner != token || !gestureOwner.compareAndSet(owner, null)) return false

        cancelOwnerDrainWatchdog()
        ownerHeartbeatAtMillis.set(0L)
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.ExpiredKeyGestureDrained(stamp))
        return true
    }

    private suspend fun actorLoop() {
        while (scope.isActive) {
            when (val message = receiveNextCoordinatorMessage(commands, ticks)) {
                is CoordinatorMailboxMessage.Control -> dispatchCommand(message.value)
                is CoordinatorMailboxMessage.LatestTick -> handleTick(message.value)
            }
        }
    }

    private fun dispatchCommand(command: Command) {
        when (command) {
            is Command.Arm -> handleArm(command)
            is Command.Disarm -> handleDisarm(command)
            is Command.Retry -> handleRetry(command)
            is Command.ForegroundChanged -> handleForegroundChanged(command)
            is Command.AccessibilityChanged -> handleAccessibilityChanged(command)
            is Command.SettingsChanged -> handleSettingsChanged(command)
            is Command.KeyDown -> handleKeyDown(command)
            is Command.KeyUp -> handleKeyUp(command)
            is Command.Tick -> handleTick(command)
            is Command.WatchdogExpired -> handleWatchdogExpired(command)
            is Command.OwnerDrainExpired -> handleOwnerDrainExpired(command)
            is Command.ExpiredKeyGestureDrained -> handleExpiredKeyGestureDrained(command)
            is Command.EnvironmentChanged -> handleEnvironmentChanged(command)
            is Command.RefreshSnapshot -> handleRefreshSnapshot(command)
            is Command.ObserveSnapshot -> handleObserveSnapshot(command)
            is Command.SetFixedVolume -> handleSetFixedVolume(command)
            is Command.VerifyFixedVolume -> verifyFixedVolume(command)
            is Command.VerifyWrite -> verifyWrite(command)
        }
    }

    private fun handleArm(command: Command.Arm) {
        if (!adoptTransition(command.stamp)) return
        mappingState = null
        publish {
            copy(
                isArmed = true,
                isFailOpen = false,
                snapshot = null,
                logicalPosition = null,
                expectedIndex = null,
                consecutiveWriteFailures = 0,
                statusMessage = if (isAccessibilityConnected) {
                    localizedText(R.string.runtime_probing_volume)
                } else {
                    localizedText(R.string.runtime_waiting_accessibility)
                },
            )
        }
        refreshSnapshotInternal(command.stamp, allowRouteChange = true)
    }

    private fun handleDisarm(command: Command.Disarm) {
        if (!adoptTransition(command.stamp)) return
        publish {
            copy(
                isArmed = false,
                isFailOpen = false,
                logicalPosition = mappingState?.let(reducer::logicalPosition),
                statusMessage = command.reason,
            )
        }
    }

    private fun handleRetry(command: Command.Retry) {
        if (!adoptTransition(command.stamp)) return
        mappingState = null
        publish {
            copy(
                isFailOpen = false,
                snapshot = null,
                logicalPosition = null,
                expectedIndex = null,
                consecutiveWriteFailures = 0,
                statusMessage = localizedText(R.string.runtime_reprobing_volume),
            )
        }
        refreshSnapshotInternal(command.stamp, allowRouteChange = true)
    }

    private fun handleForegroundChanged(command: Command.ForegroundChanged) {
        if (!adoptTransition(command.stamp)) return
        publish {
            copy(
                isForegroundServiceRunning = command.running,
                isArmed = if (command.running) isArmed else false,
                statusMessage = if (command.running) {
                    statusMessage
                } else {
                    localizedText(R.string.runtime_controller_stopped)
                },
            )
        }
    }

    private fun handleAccessibilityChanged(command: Command.AccessibilityChanged) {
        if (!adoptTransition(command.stamp)) return
        publish {
            copy(
                isAccessibilityConnected = command.connected,
                statusMessage = when {
                    command.connected && isForegroundServiceRunning ->
                        localizedText(R.string.runtime_ready)
                    command.connected -> localizedText(R.string.runtime_accessibility_connected)
                    else -> localizedText(R.string.runtime_enable_accessibility)
                },
            )
        }
        if (command.connected) {
            refreshSnapshotInternal(command.stamp, allowRouteChange = true)
        }
    }

    private fun handleSettingsChanged(command: Command.SettingsChanged) {
        if (!adoptTransition(command.stamp)) return
        settings = command.settings
        val range = _runtime.value.snapshot?.range ?: RouteVolumeRange(0, 0)
        boundStepMap = settings.outputMap.bind(range)
        reducer = VolumeMappingReducer(boundStepMap, settings.keyConfig)
        mappingState = null
        publish {
            copy(
                logicalPosition = null,
                expectedIndex = snapshot?.currentIndex,
                statusMessage = if (canInterceptKeys) {
                    localizedText(R.string.runtime_curve_updated)
                } else {
                    statusMessage
                },
            )
        }
    }

    private fun handleKeyDown(command: Command.KeyDown) {
        if (!isGestureStartCurrent(command)) return
        supersedeFixedVolumeRequestForPhysicalKey()

        // API 28-30 has no public mode-change listener. Refresh on the actor (never in the
        // Accessibility callback), then recheck the epoch before touching route/volume state.
        val mediaContextSafe = backend.refreshMediaContextSafety()
        if (!isStampCurrent(command.stamp)) return
        if (!mediaContextSafe) {
            invalidateForUnsafeMediaContext()
            return
        }

        val previousSnapshot = _runtime.value.snapshot
        val previousExpected = _runtime.value.expectedIndex

        // First epoch check is above; the second one immediately follows the blocking I/O.
        val observedSnapshot = backend.snapshot().getOrElse { throwable ->
            if (isGestureStartCurrent(command)) {
                registerFailure(
                    localizedText(
                        R.string.runtime_read_volume_failed,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                    command.stamp,
                )
                startOwnerDrainWatchdog(command.token)
            }
            return
        }
        if (!isGestureStartCurrent(command)) return

        if (!acceptSnapshot(observedSnapshot, command.stamp, allowRouteChange = false)) return
        if (
            !isGestureStartCurrent(command) ||
            isEffectivelyFixedVolume(backend.isVolumeFixed, observedSnapshot.range)
        ) {
            startOwnerDrainWatchdog(command.token)
            return
        }

        val reducerRebound = ensureReducerBound(observedSnapshot.range)
        val canKeepExactPosition = !reducerRebound && canKeepMappingPosition(
            mappingState = mappingState,
            mappingTargetIndex = mappingState?.let(reducer::targetIndex),
            previousExpectedIndex = previousExpected,
            previousSnapshot = previousSnapshot,
            observedSnapshot = observedSnapshot,
        )

        val initial = if (canKeepExactPosition) {
            checkNotNull(mappingState)
        } else {
            reducer.initialState(observedSnapshot.currentIndex)
        }
        val reduction = reducer.reduce(
            initial,
            VolumeMappingAction.KeyDown(
                direction = command.direction,
                eventTimeMillis = command.eventTimeMillis,
                repeated = false,
            ),
        )

        val gesture = ActiveGesture(
            token = command.token,
            direction = command.direction,
            stamp = command.stamp,
            routeId = observedSnapshot.route.stableId,
            rangeIdentity = observedSnapshot.range.identity,
        )
        mappingState = reduction.state
        activeGesture = gesture
        lastEnvironmentGuardAtMillis = SystemClock.uptimeMillis()
        publish { copy(logicalPosition = reducer.logicalPosition(reduction.state)) }

        if (reduction.writeRequested) {
            applyTarget(reduction.targetIndex, force = true, context = gesture.writeContext)
        }
        if (isActiveGestureCurrent(gesture)) startTicker(gesture)
    }

    private fun handleKeyUp(command: Command.KeyUp) {
        val gesture = activeGesture
        if (gesture == null || gesture.token != command.token) {
            // The gesture may already have been cancelled by an invalidation; UP still drained it.
            return
        }

        if (!isWriteContextCurrent(gesture.writeContext)) {
            cancelActiveGesture(resetMapping = false)
            return
        }

        val state = mappingState
        if (state == null) {
            cancelActiveGesture(resetMapping = false)
            return
        }
        val reduction = reducer.reduce(
            state,
            VolumeMappingAction.KeyUp(command.direction, command.eventTimeMillis),
        )
        mappingState = reduction.state
        cancelTicker()
        activeGesture = null
        writeQueue.clearPending()
        publish { copy(logicalPosition = reducer.logicalPosition(reduction.state)) }

        // Always converge to the reducer's final integer. The latest tick may already have moved
        // state while its write was throttled; in that case UP itself need not create a new
        // reduction, but it must still supersede queued targets. applyTarget is a no-op when the
        // expected index is already current.
        applyTarget(reduction.targetIndex, force = true, context = gesture.writeContext)
    }

    private fun handleTick(command: Command.Tick) {
        val gesture = activeGesture ?: return
        if (gesture.token != command.token || gesture.stamp != command.stamp) return
        // UP clears callback ownership before its reliable actor command is handled. A tick must
        // never cancel that gesture: KeyUp owns final time integration, while watchdog/transition
        // commands own other cleanup paths.
        if (!shouldProcessCoordinatorTick(gestureOwner.get(), gesture.token)) return
        if (!isWriteContextCurrent(gesture.writeContext)) return
        if (!guardActiveEnvironment(gesture, command.nowMillis)) return
        // The environment guard can perform Binder I/O while UP arrives on the callback thread.
        if (!shouldProcessCoordinatorTick(gestureOwner.get(), gesture.token)) return
        if (!isWriteContextCurrent(gesture.writeContext)) return

        val state = mappingState ?: return
        val reduction = reducer.reduce(state, VolumeMappingAction.AdvanceTime(command.nowMillis))
        mappingState = reduction.state
        publish { copy(logicalPosition = reducer.logicalPosition(reduction.state)) }
        if (reduction.writeRequested) {
            applyTarget(reduction.targetIndex, force = false, context = gesture.writeContext)
        }

        val pending = writeQueue.takeNextIfReady(SystemClock.uptimeMillis())
        if (pending != null && pending.context == gesture.writeContext) {
            writeTarget(pending.targetIndex, pending.context)
        }
    }

    private fun handleWatchdogExpired(command: Command.WatchdogExpired) {
        val gesture = activeGesture ?: return
        if (gesture.token != command.token || gesture.stamp != command.stamp) return

        val lastHeartbeat = ownerHeartbeatAtMillis.get()
        if (command.nowMillis - lastHeartbeat < LOST_UP_WATCHDOG_MILLIS) return
        if (!gestureOwner.compareAndSet(command.token, null)) return

        cancelOwnerDrainWatchdog()
        cancelActiveGesture(resetMapping = false)
        publish { copy(statusMessage = localizedText(R.string.runtime_up_timeout)) }
    }

    private fun handleOwnerDrainExpired(command: Command.OwnerDrainExpired) {
        val currentOwner = gestureOwner.get()
        if (currentOwner != null && currentOwner != command.token) return
        if (activeGesture?.token == command.token) {
            cancelActiveGesture(resetMapping = false)
        }
        publish { copy(statusMessage = localizedText(R.string.runtime_stale_gesture_cleared)) }
    }

    private fun handleExpiredKeyGestureDrained(command: Command.ExpiredKeyGestureDrained) {
        if (!adoptTransition(command.stamp)) return
        publish { copy(statusMessage = localizedText(R.string.runtime_delayed_gesture_ignored)) }
    }

    private fun handleEnvironmentChanged(command: Command.EnvironmentChanged) {
        if (!adoptTransition(command.stamp)) return
        mappingState = null
        publish {
            copy(
                snapshot = null,
                isMediaContextSafe = backend.isMediaContextSafe,
                logicalPosition = null,
                expectedIndex = null,
                statusMessage = localizedText(R.string.runtime_audio_environment_changed),
            )
        }
        refreshSnapshotInternal(command.stamp, allowRouteChange = true)
    }

    private fun handleRefreshSnapshot(command: Command.RefreshSnapshot) {
        if (!adoptTransition(command.stamp)) return
        mappingState = null
        refreshSnapshotInternal(command.stamp, allowRouteChange = true)
    }

    /** Non-destructive recovery read used after an explicit fixed-volume request. */
    private fun handleObserveSnapshot(command: Command.ObserveSnapshot) {
        if (!isStampCurrent(command.stamp)) return
        if (activeGesture != null || verificationCycle != null) return
        backend.refreshMediaContextSafety()
        if (!isStampCurrent(command.stamp)) return
        val observed = backend.snapshot().getOrNull() ?: return
        if (!isStampCurrent(command.stamp)) return
        if (!acceptSnapshot(observed, command.stamp, allowRouteChange = true)) return
        if (!isStampCurrent(command.stamp)) return
        synchronizeMappingToObserved(observed)
    }

    private fun handleSetFixedVolume(command: Command.SetFixedVolume) {
        if (!adoptTransition(command.stamp)) {
            rejectStaleFixedVolumeRequest(command)
            return
        }
        if (!isLatestFixedVolumeRequest(command.requestId)) return

        cancelFixedVolumeVerification()
        mappingState = null
        if (!isUiVisible()) {
            rejectFixedVolumeRequest(
                command = command,
                message = localizedText(R.string.fixed_volume_requires_visible_app),
            )
            return
        }
        val mediaContextSafe = backend.refreshMediaContextSafety()
        if (!isStampCurrent(command.stamp)) {
            rejectStaleFixedVolumeRequest(command)
            return
        }
        if (!mediaContextSafe) {
            rejectFixedVolumeRequest(
                command = command,
                message = localizedText(R.string.runtime_unsafe_media_context),
            )
            return
        }

        val beforeWrite = backend.snapshot().getOrElse { throwable ->
            if (isStampCurrent(command.stamp)) {
                rejectFixedVolumeRequest(
                    command = command,
                    message = localizedText(
                        R.string.runtime_read_volume_failed,
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            } else {
                rejectStaleFixedVolumeRequest(command)
            }
            return
        }
        if (!isStampCurrent(command.stamp)) {
            rejectStaleFixedVolumeRequest(command)
            return
        }

        if (
            beforeWrite.route.stableId != command.sourceRouteId ||
            beforeWrite.range.identity != command.sourceRangeIdentity
        ) {
            rejectFixedVolumeRequest(
                command = command,
                message = localizedText(R.string.runtime_route_changed),
                observed = beforeWrite,
            )
            return
        }

        if (isEffectivelyFixedVolume(backend.isVolumeFixed, beforeWrite.range)) {
            rejectFixedVolumeRequest(
                command = command,
                message = localizedText(R.string.audio_error_fixed_volume),
                observed = beforeWrite,
            )
            return
        }
        if (!beforeWrite.range.contains(command.requestedIndex)) {
            rejectFixedVolumeRequest(
                command = command,
                message = localizedText(
                    R.string.fixed_volume_out_of_range,
                    command.requestedIndex,
                    beforeWrite.range.minIndex,
                    beforeWrite.range.maxIndex,
                ),
                observed = beforeWrite,
            )
            return
        }

        if (
            !isStampCurrent(command.stamp) ||
            !isLatestFixedVolumeRequest(command.requestId)
        ) {
            rejectStaleFixedVolumeRequest(command)
            return
        }
        if (!isUiVisible()) {
            rejectFixedVolumeRequest(
                command = command,
                message = localizedText(R.string.fixed_volume_requires_visible_app),
            )
            return
        }
        val writeResult = backend.setMediaVolume(
            index = command.requestedIndex,
            showSystemUi = settings.showSystemVolumeUi,
        )
        writeResult.onFailure { throwable ->
            val message = localizedText(
                R.string.runtime_write_rejected,
                throwable.message ?: throwable.javaClass.simpleName,
            )
            if (isStampCurrent(command.stamp)) {
                rejectFixedVolumeRequest(
                    command = command,
                    message = message,
                    observed = beforeWrite,
                )
            } else {
                rejectFixedVolumeRequestWithoutSnapshot(
                    requestId = command.requestId,
                    requestedIndex = command.requestedIndex,
                    message = message,
                )
            }
            return
        }
        if (!isLatestFixedVolumeRequest(command.requestId)) return

        scheduleFixedVolumeVerification(
            FixedVolumeVerification(
                requestId = command.requestId,
                requestedIndex = command.requestedIndex,
                routeId = beforeWrite.route.stableId,
                rangeIdentity = beforeWrite.range.identity,
            ),
        )
    }

    private fun verifyFixedVolume(command: Command.VerifyFixedVolume) {
        val verification = fixedVolumeVerification ?: return
        if (
            verification.requestId != command.requestId ||
            !isLatestFixedVolumeRequest(verification.requestId) ||
            !isFixedVolumeRequestApplying(verification.requestId)
        ) {
            return
        }

        val stamp = currentStamp()
        if (!isStampCurrent(stamp)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }

        val mediaContextSafe = backend.refreshMediaContextSafety()
        if (!isStampCurrent(stamp)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }
        if (!mediaContextSafe) {
            rejectFixedVolumeVerification(
                verification = verification,
                stamp = stamp,
                message = localizedText(R.string.runtime_unsafe_media_context),
            )
            return
        }

        val observed = backend.snapshot().getOrElse { throwable ->
            when (
                fixedVolumeSnapshotFailureDisposition(
                    isFinal = command.final,
                    isStampCurrent = isStampCurrent(stamp),
                )
            ) {
                FixedVolumeSnapshotFailureDisposition.WAIT_FOR_FINAL -> Unit
                FixedVolumeSnapshotFailureDisposition.REJECT -> {
                    rejectFixedVolumeVerification(
                        verification = verification,
                        stamp = stamp,
                        message = localizedText(
                            R.string.runtime_read_volume_failed,
                            throwable.message ?: throwable.javaClass.simpleName,
                        ),
                    )
                }

                FixedVolumeSnapshotFailureDisposition.RETRY_AFTER_TRANSITION -> {
                    retryFixedVolumeVerificationAfterTransition(command, verification)
                }
            }
            return
        }
        if (!isStampCurrent(stamp)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }

        if (
            observed.route.stableId != verification.routeId ||
            observed.range.identity != verification.rangeIdentity
        ) {
            rejectFixedVolumeVerification(
                verification = verification,
                stamp = stamp,
                message = localizedText(R.string.runtime_route_changed),
                observed = observed,
            )
            return
        }
        if (isEffectivelyFixedVolume(backend.isVolumeFixed, observed.range)) {
            rejectFixedVolumeVerification(
                verification = verification,
                stamp = stamp,
                message = localizedText(R.string.audio_error_fixed_volume),
                observed = observed,
            )
            return
        }

        if (observed.currentIndex == verification.requestedIndex) {
            completeFixedVolumeRequest(command, verification, observed, stamp)
            return
        }
        if (!command.final) return

        rejectFixedVolumeVerification(
            verification = verification,
            stamp = stamp,
            message = localizedText(
                R.string.runtime_readback_mismatch,
                verification.requestedIndex,
                observed.currentIndex,
            ),
            observed = observed,
        )
    }

    /**
     * Low-frequency active-gesture guard for mode and effective route. It covers API 28-30, where
     * AudioManager has no mode listener, and route switches that do not add/remove a device.
     * The observed current index is deliberately not published here: active logical/expected state
     * remains owned by the reducer and write verification.
     */
    private fun guardActiveEnvironment(gesture: ActiveGesture, nowMillis: Long): Boolean {
        if (
            lastEnvironmentGuardAtMillis != Long.MIN_VALUE &&
            nowMillis - lastEnvironmentGuardAtMillis < ENVIRONMENT_GUARD_INTERVAL_MILLIS
        ) {
            return true
        }
        lastEnvironmentGuardAtMillis = nowMillis

        if (!isActiveGestureCurrent(gesture)) return false
        val mediaContextSafe = backend.refreshMediaContextSafety()
        if (!isStampCurrent(gesture.stamp)) return false
        if (!mediaContextSafe) {
            invalidateForUnsafeMediaContext()
            return false
        }

        val guardedSnapshot = backend.snapshot().getOrElse {
            if (isStampCurrent(gesture.stamp)) {
                registerFailure(
                    localizedText(
                        R.string.runtime_route_check_failed,
                        it.message ?: it.javaClass.simpleName,
                    ),
                    gesture.stamp,
                )
            }
            return false
        }
        if (!isActiveGestureCurrent(gesture)) return false

        if (
            guardedSnapshot.route.stableId != gesture.routeId ||
            guardedSnapshot.range.identity != gesture.rangeIdentity
        ) {
            adoptUnexpectedRoute(guardedSnapshot)
            return false
        }
        if (isEffectivelyFixedVolume(backend.isVolumeFixed, guardedSnapshot.range)) {
            invalidateForFixedVolume(guardedSnapshot)
            return false
        }
        return true
    }

    private fun invalidateForUnsafeMediaContext() {
        val stamp = beginControlTransition()
        actorControlEpoch = stamp.controlEpoch
        cancelActorWork(resetMapping = false)
        publish {
            copy(
                isMediaContextSafe = false,
                statusMessage = localizedText(R.string.runtime_unsafe_media_context),
            )
        }
    }

    private fun invalidateForFixedVolume(snapshot: RouteVolumeSnapshot) {
        val stamp = beginControlTransition()
        actorControlEpoch = stamp.controlEpoch
        cancelActorWork(resetMapping = true)
        publish {
            copy(
                snapshot = snapshot,
                isVolumeFixed = true,
                logicalPosition = null,
                expectedIndex = snapshot.currentIndex,
                statusMessage = localizedText(R.string.runtime_fixed_volume),
            )
        }
    }

    private fun refreshSnapshotInternal(
        stamp: EpochStamp,
        allowRouteChange: Boolean = false,
    ) {
        if (!isStampCurrent(stamp)) return
        val mediaContextSafe = backend.refreshMediaContextSafety()
        if (!isStampCurrent(stamp)) return
        publish {
            copy(
                isMediaContextSafe = mediaContextSafe,
                statusMessage = if (!mediaContextSafe) {
                    localizedText(R.string.runtime_unsafe_media_context)
                } else {
                    statusMessage
                },
            )
        }
        if (!mediaContextSafe) return

        val snapshot = backend.snapshot().getOrElse {
            if (isStampCurrent(stamp)) {
                registerFailure(
                    localizedText(
                        R.string.runtime_probe_failed,
                        it.message ?: it.javaClass.simpleName,
                    ),
                    stamp,
                )
            }
            return
        }
        if (!isStampCurrent(stamp)) return
        acceptSnapshot(snapshot, stamp, allowRouteChange)
    }

    /**
     * Publishes an observed snapshot with expectedIndex forced to the observed index. A route change
     * not preceded by environmentChanges() creates a new epoch here and aborts the caller's I/O.
     */
    private fun acceptSnapshot(
        snapshot: RouteVolumeSnapshot,
        stamp: EpochStamp,
        allowRouteChange: Boolean,
    ): Boolean {
        if (!isStampCurrent(stamp)) return false
        val previousSnapshot = _runtime.value.snapshot
        if (
            !allowRouteChange &&
            previousSnapshot != null &&
            (
                previousSnapshot.route.stableId != snapshot.route.stableId ||
                    previousSnapshot.range.identity != snapshot.range.identity
                )
        ) {
            adoptUnexpectedRoute(snapshot)
            return false
        }

        publish {
            withAcceptedSnapshot(
                snapshot = snapshot,
                isVolumeFixed = isEffectivelyFixedVolume(backend.isVolumeFixed, snapshot.range),
                isMediaContextSafe = backend.isMediaContextSafe,
            )
        }
        return isStampCurrent(stamp)
    }

    private fun adoptUnexpectedRoute(snapshot: RouteVolumeSnapshot) {
        val stamp = beginControlTransition(routeChanged = true)
        actorControlEpoch = stamp.controlEpoch
        cancelActorWork(resetMapping = true)
        publish {
            copy(
                snapshot = snapshot,
                isVolumeFixed = isEffectivelyFixedVolume(backend.isVolumeFixed, snapshot.range),
                isMediaContextSafe = backend.isMediaContextSafe,
                logicalPosition = null,
                expectedIndex = snapshot.currentIndex,
                statusMessage = localizedText(R.string.runtime_route_changed),
            )
        }
    }

    private fun applyTarget(
        targetIndex: Int,
        force: Boolean,
        context: WriteContext,
    ) {
        if (!isWriteContextCurrent(context)) return
        if (force) {
            writeQueue.clearPending()
            writeTarget(targetIndex, context)
            return
        }

        val now = SystemClock.uptimeMillis()
        if (writeQueue.hasPending || !writeQueue.canStartAttempt(now)) {
            // Keep every reducer target in FIFO order. A newer adjacent tick must never replace an
            // older target merely because the Binder call shifted the throttle phase.
            writeQueue.enqueue(PendingWrite(targetIndex, context))
            return
        }
        writeTarget(targetIndex, context)
    }

    private fun writeTarget(targetIndex: Int, context: WriteContext) {
        if (!isWriteContextCurrent(context)) return
        val snapshot = _runtime.value.snapshot ?: return
        if (snapshot.route.stableId != context.routeId) return
        if (!snapshot.range.contains(targetIndex)) return
        if (targetIndex == _runtime.value.expectedIndex) return

        // Epoch/route check immediately before the blocking write.
        if (!isWriteContextCurrent(context)) return
        writeQueue.recordAttemptStarted(SystemClock.uptimeMillis())
        val result = backend.setMediaVolume(targetIndex, settings.showSystemVolumeUi)
        // A transition racing the Binder call makes its result stale, regardless of success/failure.
        if (!isWriteContextCurrent(context)) return

        result.onSuccess {
            // The settle window starts after the blocking Binder call completes, not when it
            // begins. The write queue deliberately retains the pre-call timestamp for throttling.
            val writtenAtMillis = SystemClock.uptimeMillis()
            publish {
                copy(
                    expectedIndex = targetIndex,
                    statusMessage = localizedText(
                        R.string.runtime_requested,
                        targetIndex,
                        snapshot.range.maxIndex,
                    ),
                )
            }
            scheduleVerification(context, targetIndex, writtenAtMillis)
        }.onFailure {
            registerFailure(
                localizedText(
                    R.string.runtime_write_rejected,
                    it.message ?: it.javaClass.simpleName,
                ),
                context.stamp,
            )
        }
    }

    private fun scheduleFixedVolumeVerification(verification: FixedVolumeVerification) {
        cancelFixedVolumeVerification()
        fixedVolumeVerification = verification
        val job = scope.launch {
            delay(FIRST_VERIFY_DELAY_MILLIS)
            commands.send(
                Command.VerifyFixedVolume(
                    requestId = verification.requestId,
                    final = false,
                ),
            )
            delay(FINAL_VERIFY_DELAY_MILLIS - FIRST_VERIFY_DELAY_MILLIS)
            commands.send(
                Command.VerifyFixedVolume(
                    requestId = verification.requestId,
                    final = true,
                ),
            )
        }
        fixedVolumeVerificationJob.getAndSet(job)?.cancel()
        if (
            !isLatestFixedVolumeRequest(verification.requestId) ||
            !isFixedVolumeRequestApplying(verification.requestId)
        ) {
            if (fixedVolumeVerificationJob.compareAndSet(job, null)) job.cancel()
        }
    }

    private fun retryFixedVolumeVerificationAfterTransition(
        command: Command.VerifyFixedVolume,
        verification: FixedVolumeVerification,
    ) {
        if (!command.final) return
        if (command.transitionRetries >= MAX_FIXED_VERIFICATION_TRANSITION_RETRIES) {
            rejectFixedVolumeRequestWithoutSnapshot(
                requestId = verification.requestId,
                requestedIndex = verification.requestedIndex,
                message = localizedText(R.string.fixed_volume_request_cancelled),
            )
            enqueueFixedVolumeRecoverySnapshot()
            return
        }
        scope.launch {
            delay(FIRST_VERIFY_DELAY_MILLIS)
            commands.send(
                command.copy(transitionRetries = command.transitionRetries + 1),
            )
        }
    }

    private fun completeFixedVolumeRequest(
        command: Command.VerifyFixedVolume,
        verification: FixedVolumeVerification,
        observed: RouteVolumeSnapshot,
        stamp: EpochStamp,
    ) {
        if (!isLatestFixedVolumeRequest(verification.requestId)) return
        if (!isStampCurrent(stamp)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }
        val applying = applyingFixedVolumeRequest(verification.requestId) ?: return
        if (!acceptSnapshot(observed, stamp, allowRouteChange = true)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }
        if (!isStampCurrent(stamp)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }
        synchronizeMappingToObserved(observed)
        if (!isStampCurrent(stamp)) {
            retryFixedVolumeVerificationAfterTransition(command, verification)
            return
        }
        if (!isLatestFixedVolumeRequest(verification.requestId)) return
        val completed = _fixedVolumeRequestState.compareAndSet(
            applying,
            FixedVolumeRequestState.Applied(
                requestId = verification.requestId,
                requestedIndex = verification.requestedIndex,
                observedIndex = observed.currentIndex,
            ),
        )
        if (completed) cancelFixedVolumeVerification()
    }

    private fun rejectFixedVolumeRequest(
        command: Command.SetFixedVolume,
        message: LocalizedText,
        observed: RouteVolumeSnapshot? = null,
    ) {
        rejectFixedVolumeRequest(
            requestId = command.requestId,
            requestedIndex = command.requestedIndex,
            stamp = command.stamp,
            message = message,
            observed = observed,
        )
    }

    private fun rejectFixedVolumeVerification(
        verification: FixedVolumeVerification,
        stamp: EpochStamp,
        message: LocalizedText,
        observed: RouteVolumeSnapshot? = null,
    ) {
        rejectFixedVolumeRequest(
            requestId = verification.requestId,
            requestedIndex = verification.requestedIndex,
            stamp = stamp,
            message = message,
            observed = observed,
        )
        if (observed == null) enqueueFixedVolumeRecoverySnapshot()
    }

    private fun rejectFixedVolumeRequest(
        requestId: Long,
        requestedIndex: Int,
        stamp: EpochStamp,
        message: LocalizedText,
        observed: RouteVolumeSnapshot?,
    ) {
        if (!isLatestFixedVolumeRequest(requestId)) return
        if (!isStampCurrent(stamp)) {
            rejectFixedVolumeRequestWithoutSnapshot(requestId, requestedIndex, message)
            enqueueFixedVolumeRecoverySnapshot()
            return
        }
        val applying = applyingFixedVolumeRequest(requestId) ?: return
        if (observed != null) {
            if (!acceptSnapshot(observed, stamp, allowRouteChange = true)) {
                rejectFixedVolumeRequestWithoutSnapshot(requestId, requestedIndex, message)
                enqueueFixedVolumeRecoverySnapshot()
                return
            }
            if (!isStampCurrent(stamp)) {
                rejectFixedVolumeRequestWithoutSnapshot(requestId, requestedIndex, message)
                enqueueFixedVolumeRecoverySnapshot()
                return
            }
            synchronizeMappingToObserved(observed)
        } else {
            // beginControlTransition() made callback eligibility false. Publish even when no fresh
            // snapshot is available so a prior coherent mapping state can become eligible again.
            publish { copy(isMediaContextSafe = backend.isMediaContextSafe) }
        }
        if (!isLatestFixedVolumeRequest(requestId)) return
        if (!isStampCurrent(stamp)) {
            rejectFixedVolumeRequestWithoutSnapshot(requestId, requestedIndex, message)
            enqueueFixedVolumeRecoverySnapshot()
            return
        }
        val rejected = _fixedVolumeRequestState.compareAndSet(
            applying,
            FixedVolumeRequestState.Rejected(
                requestId = requestId,
                requestedIndex = requestedIndex,
                message = message,
                observedIndex = observed?.currentIndex,
            ),
        )
        if (rejected) cancelFixedVolumeVerification()
    }

    private fun rejectStaleFixedVolumeRequest(command: Command.SetFixedVolume) {
        rejectFixedVolumeRequestWithoutSnapshot(
            requestId = command.requestId,
            requestedIndex = command.requestedIndex,
            message = localizedText(R.string.fixed_volume_request_cancelled),
        )
    }

    private fun enqueueFixedVolumeRecoverySnapshot() {
        enqueueGuaranteed(Command.ObserveSnapshot(currentStamp()))
    }

    private fun rejectFixedVolumeRequestWithoutSnapshot(
        requestId: Long,
        requestedIndex: Int,
        message: LocalizedText,
    ) {
        if (!isLatestFixedVolumeRequest(requestId)) return
        val applying = applyingFixedVolumeRequest(requestId) ?: return
        val rejected = _fixedVolumeRequestState.compareAndSet(
            applying,
            FixedVolumeRequestState.Rejected(
                requestId = requestId,
                requestedIndex = requestedIndex,
                message = message,
            ),
        )
        if (rejected) cancelFixedVolumeVerification()
    }

    private fun applyingFixedVolumeRequest(requestId: Long): FixedVolumeRequestState.Applying? =
        (_fixedVolumeRequestState.value as? FixedVolumeRequestState.Applying)
            ?.takeIf { state -> state.requestId == requestId }

    private fun isFixedVolumeRequestApplying(requestId: Long): Boolean =
        applyingFixedVolumeRequest(requestId) != null

    private fun supersedeFixedVolumeRequestForPhysicalKey() {
        val applying = _fixedVolumeRequestState.value as? FixedVolumeRequestState.Applying ?: return
        if (_fixedVolumeRequestState.compareAndSet(applying, FixedVolumeRequestState.Idle)) {
            cancelFixedVolumeVerification()
        }
    }

    private fun isLatestFixedVolumeRequest(requestId: Long): Boolean =
        fixedVolumeRequestSequence.get() == requestId

    private fun isUiVisible(): Boolean = startedActivityCount.get() > 0

    /** One fixed verification cycle tracks the latest target instead of restarting per write. */
    private fun scheduleVerification(
        context: WriteContext,
        targetIndex: Int,
        writtenAtMillis: Long,
    ) {
        if (!isWriteContextCurrent(context)) return
        val existing = verificationCycle
        if (existing != null && existing.context == context) {
            existing.latestTargetIndex = targetIndex
            existing.latestWriteAtMillis = writtenAtMillis
            return
        }

        cancelVerification()
        val cycle = VerificationCycle(
            id = ++nextVerificationId,
            context = context,
            latestTargetIndex = targetIndex,
            latestWriteAtMillis = writtenAtMillis,
        )
        verificationCycle = cycle

        val job = scope.launch {
            delay(FIRST_VERIFY_DELAY_MILLIS)
            commands.send(Command.VerifyWrite(cycle.id, final = false))
            delay(FINAL_VERIFY_DELAY_MILLIS - FIRST_VERIFY_DELAY_MILLIS)
            commands.send(Command.VerifyWrite(cycle.id, final = true))
        }
        verificationJob.getAndSet(job)?.cancel()
        if (!isWriteContextCurrent(context) || verificationCycle?.id != cycle.id) {
            if (verificationJob.compareAndSet(job, null)) job.cancel()
        }
    }

    private fun verifyWrite(command: Command.VerifyWrite) {
        val cycle = verificationCycle ?: return
        if (cycle.id != command.cycleId || !isWriteContextCurrent(cycle.context)) return

        val mediaContextSafe = backend.refreshMediaContextSafety()
        if (!isStampCurrent(cycle.context.stamp) || verificationCycle?.id != cycle.id) return
        if (!mediaContextSafe) {
            invalidateForUnsafeMediaContext()
            return
        }

        // Epoch/route checks bracket this read I/O just like a write.
        val observed = backend.snapshot().getOrElse {
            if (command.final && verificationCycle?.id == cycle.id) {
                cancelVerification()
                registerFailure(
                    localizedText(R.string.runtime_read_after_write_failed),
                    cycle.context.stamp,
                )
            }
            return
        }
        if (!isWriteContextCurrent(cycle.context) || verificationCycle?.id != cycle.id) return
        if (
            observed.route.stableId != cycle.context.routeId ||
            observed.range.identity != cycle.context.rangeIdentity
        ) {
            adoptUnexpectedRoute(observed)
            return
        }
        if (isEffectivelyFixedVolume(backend.isVolumeFixed, observed.range)) {
            invalidateForFixedVolume(observed)
            return
        }

        val targetIndex = cycle.latestTargetIndex
        if (observed.currentIndex == targetIndex) {
            cancelVerification()
            publish {
                copy(
                    snapshot = observed,
                    isVolumeFixed = false,
                    isMediaContextSafe = backend.isMediaContextSafe,
                    expectedIndex = observed.currentIndex,
                    consecutiveWriteFailures = 0,
                    statusMessage = localizedText(
                        R.string.runtime_write_confirmed,
                        observed.currentIndex,
                        observed.range.maxIndex,
                    ),
                )
            }
            return
        }

        val latestWriteAge = SystemClock.uptimeMillis() - cycle.latestWriteAtMillis
        if (latestWriteAge < FINAL_VERIFY_DELAY_MILLIS) {
            // This snapshot can predate the latest write in a continuous hold. Keep the exact
            // reducer slot, fractional hold remainder and any newer throttled target until the
            // newest write has received the complete settling window.
            if (command.final) {
                if (activeGesture?.writeContext == cycle.context) {
                    // A fixed-duration cycle still probes a continuous hold. Start a fresh cycle
                    // instead of letting per-write generations postpone verification forever.
                    val latestTarget = cycle.latestTargetIndex
                    val latestWrittenAt = cycle.latestWriteAtMillis
                    cancelVerification()
                    scheduleVerification(cycle.context, latestTarget, latestWrittenAt)
                } else {
                    deferFinalVerification(
                        cycle,
                        FINAL_VERIFY_DELAY_MILLIS - latestWriteAge,
                    )
                }
            }
            return
        }

        publish {
            copy(
                snapshot = observed,
                isVolumeFixed = false,
                isMediaContextSafe = backend.isMediaContextSafe,
                expectedIndex = observed.currentIndex,
            )
        }
        synchronizeMappingToObserved(observed)
        if (!command.final) return

        cancelVerification()
        registerFailure(
            localizedText(
                R.string.runtime_readback_mismatch,
                targetIndex,
                observed.currentIndex,
            ),
            cycle.context.stamp,
        )
        if (!isStampCurrent(cycle.context.stamp)) return
    }

    private fun synchronizeMappingToObserved(observed: RouteVolumeSnapshot) {
        // A mature mismatch makes every throttled target derived from the old anchor stale.
        writeQueue.clearPending()
        ensureReducerBound(observed.range)
        val state = mappingState
        mappingState = if (state == null) {
            reducer.initialState(observed.currentIndex)
        } else {
            reducer.reduce(
                state,
                VolumeMappingAction.SynchronizeObserved(
                    observedIndex = observed.currentIndex,
                    forceWhilePressed = true,
                ),
            ).state
        }
        publish { copy(logicalPosition = mappingState?.let(reducer::logicalPosition)) }
    }

    /** Rebuilds the route-specific step table when the effective integer range changes. */
    private fun ensureReducerBound(range: RouteVolumeRange): Boolean {
        if (
            boundStepMap.source == settings.outputMap &&
            boundStepMap.range.hasSameIndexBounds(range)
        ) {
            return false
        }
        val nextBoundMap = settings.outputMap.bind(range)
        boundStepMap = nextBoundMap
        reducer = VolumeMappingReducer(boundStepMap, settings.keyConfig)
        mappingState = null
        return true
    }

    private fun registerFailure(message: LocalizedText, stamp: EpochStamp) {
        if (!isStampCurrent(stamp)) return
        val failures = _runtime.value.consecutiveWriteFailures + 1
        val failOpen = failures >= FAILURE_LIMIT
        if (failOpen) {
            val invalidated = beginControlTransition()
            actorControlEpoch = invalidated.controlEpoch
            cancelActorWork(resetMapping = false)
        }
        publish {
            copy(
                consecutiveWriteFailures = failures,
                isFailOpen = failOpen,
                statusMessage = if (failOpen) {
                    localizedText(R.string.runtime_fail_open, message)
                } else {
                    message
                },
            )
        }
    }

    /** Applies the already-published atomic transition to actor-owned mutable state. */
    private fun adoptTransition(stamp: EpochStamp): Boolean {
        if (stamp.controlEpoch < actorControlEpoch) return false
        actorControlEpoch = stamp.controlEpoch
        cancelActorWork(resetMapping = false)
        return true
    }

    private fun cancelActorWork(resetMapping: Boolean) {
        cancelTicker()
        cancelVerification()
        writeQueue.reset()
        lastEnvironmentGuardAtMillis = Long.MIN_VALUE
        activeGesture = null
        mappingState = if (resetMapping) {
            null
        } else {
            mappingState?.let { reducer.reduce(it, VolumeMappingAction.CancelPress).state }
        }
    }

    private fun cancelActiveGesture(resetMapping: Boolean) {
        cancelTicker()
        writeQueue.clearPending()
        activeGesture = null
        mappingState = if (resetMapping) {
            null
        } else {
            mappingState?.let { reducer.reduce(it, VolumeMappingAction.CancelPress).state }
        }
    }

    private fun startTicker(gesture: ActiveGesture) {
        cancelTicker()
        val job = scope.launch {
            while (isActive) {
                if (gestureOwner.get() != gesture.token) break

                val now = SystemClock.uptimeMillis()
                if (now - ownerHeartbeatAtMillis.get() >= LOST_UP_WATCHDOG_MILLIS) {
                    commands.send(Command.WatchdogExpired(gesture.token, gesture.stamp, now))
                    break
                }
                // Absolute-time integration makes intermediate ticks disposable. An immediate
                // first offer also catches up without another polling delay after slow Binder I/O.
                ticks.trySend(Command.Tick(gesture.token, gesture.stamp, now))
                delay(COORDINATOR_TICK_INTERVAL_MILLIS)
            }
        }
        tickerJob.getAndSet(job)?.cancel()

        // Close the install-after-invalidate race.
        if (!isActiveGestureCurrent(gesture)) {
            if (tickerJob.compareAndSet(job, null)) job.cancel()
        }
    }

    private fun cancelTicker() {
        tickerJob.getAndSet(null)?.cancel()
    }

    /** A slow watchdog for a consumed owner whose actor gesture was cancelled before UP. */
    private fun startOwnerDrainWatchdog(token: KeyToken) {
        if (gestureOwner.get() != token) return
        val job = scope.launch {
            while (isActive && gestureOwner.get() == token) {
                val elapsed = SystemClock.uptimeMillis() - ownerHeartbeatAtMillis.get()
                val remaining = LOST_UP_WATCHDOG_MILLIS - elapsed
                if (remaining > 0L) {
                    delay(remaining)
                    continue
                }
                if (gestureOwner.compareAndSet(token, null)) {
                    commands.send(Command.OwnerDrainExpired(token))
                }
                break
            }
        }
        ownerDrainWatchdogJob.getAndSet(job)?.cancel()
        if (gestureOwner.get() != token) {
            if (ownerDrainWatchdogJob.compareAndSet(job, null)) job.cancel()
        }
    }

    private fun cancelOwnerDrainWatchdog() {
        ownerDrainWatchdogJob.getAndSet(null)?.cancel()
    }

    private fun cancelVerification() {
        verificationCycle = null
        verificationJob.getAndSet(null)?.cancel()
    }

    private fun cancelFixedVolumeVerification() {
        fixedVolumeVerification = null
        fixedVolumeVerificationJob.getAndSet(null)?.cancel()
    }

    private fun deferFinalVerification(cycle: VerificationCycle, delayMillis: Long) {
        if (verificationCycle?.id != cycle.id || !isWriteContextCurrent(cycle.context)) return
        val job = scope.launch {
            delay(delayMillis.coerceAtLeast(1L))
            commands.send(Command.VerifyWrite(cycle.id, final = true))
        }
        verificationJob.getAndSet(job)?.cancel()
        if (!isWriteContextCurrent(cycle.context) || verificationCycle?.id != cycle.id) {
            if (verificationJob.compareAndSet(job, null)) job.cancel()
        }
    }

    /**
     * Invalidates callback eligibility before its actor command is enqueued. Async jobs are also
     * cancelled here; actor-owned pending state is rendered inert by the incremented epoch.
     */
    private fun beginControlTransition(
        routeChanged: Boolean = false,
        clearOwner: Boolean = false,
    ): EpochStamp {
        eligible.set(false)
        val nextRouteEpoch = if (routeChanged) {
            routeEpoch.incrementAndGet()
        } else {
            routeEpoch.get()
        }
        val nextControlEpoch = controlEpoch.incrementAndGet()
        tickerJob.getAndSet(null)?.cancel()
        verificationJob.getAndSet(null)?.cancel()
        if (clearOwner) {
            cancelOwnerDrainWatchdog()
            gestureOwner.set(null)
            ownerHeartbeatAtMillis.set(0L)
        } else {
            gestureOwner.get()?.let(::startOwnerDrainWatchdog)
        }
        return EpochStamp(nextControlEpoch, nextRouteEpoch)
    }

    private fun enqueueGuaranteed(command: Command) {
        if (!commands.trySend(command).isSuccess) {
            // UNDISPATCHED reaches Channel.send before returning; suspended senders therefore keep
            // caller order even when the bounded queue is temporarily full.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { commands.send(command) }
        }
    }

    private fun currentStamp(): EpochStamp = EpochStamp(controlEpoch.get(), routeEpoch.get())

    private fun isStampCurrent(stamp: EpochStamp): Boolean =
        actorControlEpoch == stamp.controlEpoch &&
            controlEpoch.get() == stamp.controlEpoch &&
            routeEpoch.get() == stamp.routeEpoch

    private fun isGestureStartCurrent(command: Command.KeyDown): Boolean =
        // A fast UP can drain callback ownership before the actor receives this already-accepted
        // DOWN. Channel ordering still delivers DOWN before UP, so epoch/eligibility determines
        // whether the tap is current; gestureOwner only governs continued hold/ticker ownership.
        isStampCurrent(command.stamp) && eligible.get() && backend.isMediaContextSafe

    private fun isActiveGestureCurrent(gesture: ActiveGesture): Boolean =
        activeGesture == gesture &&
            gestureOwner.get() == gesture.token &&
            isWriteContextCurrent(gesture.writeContext)

    private fun isWriteContextCurrent(context: WriteContext): Boolean {
        if (!isStampCurrent(context.stamp)) return false
        if (!eligible.get() || !backend.isMediaContextSafe) return false
        val snapshot = _runtime.value.snapshot ?: return false
        if (snapshot.route.stableId != context.routeId) return false
        if (snapshot.range.identity != context.rangeIdentity) return false
        return true
    }

    private val RouteVolumeRange.identity: VolumeRangeIdentity
        get() = VolumeRangeIdentity(minIndex = minIndex, maxIndex = maxIndex)

    private inline fun publish(transform: ControllerRuntimeState.() -> ControllerRuntimeState) {
        _runtime.update {
            it.transform().copy(
                isArmed = desiredArmed.get(),
                isForegroundServiceRunning = desiredForegroundRunning.get(),
                isAccessibilityConnected = desiredAccessibilityConnected.get(),
                lastUpdatedAtMillis = SystemClock.elapsedRealtime(),
            )
        }
        val state = _runtime.value
        val publishedEpoch = controlEpoch.get()
        eligible.set(
            actorControlEpoch == publishedEpoch &&
                state.canInterceptKeys &&
                backend.isMediaContextSafe,
        )
        // Close publish(true)-after-invalidate(false): a transition that raced the set always wins.
        if (controlEpoch.get() != publishedEpoch) eligible.set(false)
    }

    private fun Int.toDirection(): VolumeDirection? = when (this) {
        KeyEvent.KEYCODE_VOLUME_UP -> VolumeDirection.UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeDirection.DOWN
        else -> null
    }

    private data class EpochStamp(
        val controlEpoch: Long,
        val routeEpoch: Long,
    )

    private data class WriteContext(
        val stamp: EpochStamp,
        val routeId: String,
        val rangeIdentity: VolumeRangeIdentity,
    )

    private data class ActiveGesture(
        val token: KeyToken,
        val direction: VolumeDirection,
        val stamp: EpochStamp,
        val routeId: String,
        val rangeIdentity: VolumeRangeIdentity,
    ) {
        val writeContext: WriteContext
            get() = WriteContext(stamp, routeId, rangeIdentity)
    }

    private data class VolumeRangeIdentity(
        val minIndex: Int,
        val maxIndex: Int,
    )

    private data class PendingWrite(
        val targetIndex: Int,
        val context: WriteContext,
    )

    private data class VerificationCycle(
        val id: Long,
        val context: WriteContext,
        var latestTargetIndex: Int,
        var latestWriteAtMillis: Long,
    )

    private data class FixedVolumeVerification(
        val requestId: Long,
        val requestedIndex: Int,
        val routeId: String,
        val rangeIdentity: VolumeRangeIdentity,
    )

    private sealed interface Command {
        data class Arm(val stamp: EpochStamp) : Command
        data class Disarm(val reason: LocalizedText, val stamp: EpochStamp) : Command
        data class Retry(val stamp: EpochStamp) : Command
        data class ForegroundChanged(val running: Boolean, val stamp: EpochStamp) : Command
        data class AccessibilityChanged(val connected: Boolean, val stamp: EpochStamp) : Command
        data class SettingsChanged(
            val settings: VolumeMapperSettings,
            val stamp: EpochStamp,
        ) : Command

        data class KeyDown(
            val token: KeyToken,
            val direction: VolumeDirection,
            val eventTimeMillis: Long,
            val stamp: EpochStamp,
        ) : Command

        data class KeyUp(
            val token: KeyToken,
            val direction: VolumeDirection,
            val eventTimeMillis: Long,
        ) : Command

        data class Tick(
            val token: KeyToken,
            val stamp: EpochStamp,
            val nowMillis: Long,
        ) : Command

        data class WatchdogExpired(
            val token: KeyToken,
            val stamp: EpochStamp,
            val nowMillis: Long,
        ) : Command

        data class OwnerDrainExpired(val token: KeyToken) : Command

        data class ExpiredKeyGestureDrained(val stamp: EpochStamp) : Command

        data class EnvironmentChanged(val stamp: EpochStamp) : Command
        data class RefreshSnapshot(val stamp: EpochStamp) : Command
        data class ObserveSnapshot(val stamp: EpochStamp) : Command
        data class SetFixedVolume(
            val requestId: Long,
            val requestedIndex: Int,
            val sourceRouteId: String,
            val sourceRangeIdentity: VolumeRangeIdentity,
            val stamp: EpochStamp,
        ) : Command

        data class VerifyFixedVolume(
            val requestId: Long,
            val final: Boolean,
            val transitionRetries: Int = 0,
        ) : Command

        data class VerifyWrite(val cycleId: Long, val final: Boolean) : Command
    }

    private companion object {
        const val COMMAND_BUFFER_CAPACITY = 64
        const val LOST_UP_WATCHDOG_MILLIS = 2_000L
        const val ENVIRONMENT_GUARD_INTERVAL_MILLIS = 500L
        const val FIRST_VERIFY_DELAY_MILLIS = 80L
        const val FINAL_VERIFY_DELAY_MILLIS = 380L
        const val MAX_FIXED_VERIFICATION_TRANSITION_RETRIES = 3
        const val FAILURE_LIMIT = 3

        fun maxLong(left: Long, right: Long): Long = maxOf(left, right)
    }
}

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
