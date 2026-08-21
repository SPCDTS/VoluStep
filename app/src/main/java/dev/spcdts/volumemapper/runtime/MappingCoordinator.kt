package dev.spcdts.volumemapper.runtime

import android.os.SystemClock
import android.view.KeyEvent
import dev.spcdts.volumemapper.audio.AudioManagerVolumeBackend
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.VolumeDirection
import dev.spcdts.volumemapper.core.VolumeMappingAction
import dev.spcdts.volumemapper.core.VolumeMappingReducer
import dev.spcdts.volumemapper.core.VolumeMappingState
import dev.spcdts.volumemapper.core.VolumeQuantizer
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import java.util.concurrent.atomic.AtomicBoolean
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
    val statusMessage: String = "尚未启用映射",
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

/** 唯一标识一次物理按键手势；repeat 和 UP 必须携带与初始 DOWN 相同的 downTime。 */
internal data class KeyToken(
    val deviceId: Int,
    val keyCode: Int,
    val downTimeMillis: Long,
)

/** Pure continuity decision kept outside the actor so route/readback edge cases are unit-testable. */
internal fun canKeepLogicalRemainder(
    mappingState: VolumeMappingState?,
    previousExpectedIndex: Int?,
    previousSnapshot: RouteVolumeSnapshot?,
    observedSnapshot: RouteVolumeSnapshot,
): Boolean =
    mappingState != null &&
        mappingState.activePress == null &&
        previousExpectedIndex == observedSnapshot.currentIndex &&
        previousSnapshot?.route?.stableId == observedSnapshot.route.stableId &&
        previousSnapshot.range == observedSnapshot.range

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

    /** Callback-side state. None of these reads performs Binder I/O. */
    private val eligible = AtomicBoolean(false)
    private val desiredArmed = AtomicBoolean(false)
    private val desiredForegroundRunning = AtomicBoolean(false)
    private val desiredAccessibilityConnected = AtomicBoolean(false)
    private val controlEpoch = AtomicLong(0L)
    private val routeEpoch = AtomicLong(0L)
    private val gestureOwner = AtomicReference<KeyToken?>(null)
    private val ownerHeartbeatAtMillis = AtomicLong(0L)
    private val tickerJob = AtomicReference<Job?>(null)
    private val ownerDrainWatchdogJob = AtomicReference<Job?>(null)
    private val verificationJob = AtomicReference<Job?>(null)
    private val observedSettings = AtomicReference(settingsRepository.settings.value)

    private val _runtime = MutableStateFlow(ControllerRuntimeState())
    val runtime: StateFlow<ControllerRuntimeState> = _runtime

    /** Actor-side state. */
    private var actorControlEpoch = 0L
    private var settings: VolumeMapperSettings = observedSettings.get()
    private var reducer = VolumeMappingReducer(settings.outputCurve, settings.keyConfig)
    private var mappingState: VolumeMappingState? = null
    private var activeGesture: ActiveGesture? = null
    private var lastWriteAtMillis = Long.MIN_VALUE
    private var lastEnvironmentGuardAtMillis = Long.MIN_VALUE
    private var pendingWrite: PendingWrite? = null
    private var verificationCycle: VerificationCycle? = null
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
        enqueueGuaranteed(Command.Disarm("映射已由用户停止", stamp))
    }

    fun retry() {
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.Retry(stamp))
    }

    fun refreshSnapshot() {
        val stamp = beginControlTransition()
        enqueueGuaranteed(Command.RefreshSnapshot(stamp))
    }

    /**
     * 返回 true 即消费完整手势。一次初始 DOWN 被消费后，即使控制状态随后失效，也会继续消费
     * 同一 [KeyToken] 的 repeat 和 UP；Accessibility 断连是唯一会立即清除 owner 的控制变化。
     */
    fun handleAccessibilityKey(event: KeyEvent): Boolean {
        val direction = event.keyCode.toDirection() ?: return false
        val token = KeyToken(event.deviceId, event.keyCode, event.downTime)

        return when (event.action) {
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

    private suspend fun actorLoop() {
        for (command in commands) {
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
                is Command.EnvironmentChanged -> handleEnvironmentChanged(command)
                is Command.RefreshSnapshot -> handleRefreshSnapshot(command)
                is Command.VerifyWrite -> verifyWrite(command)
            }
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
                    "正在探测媒体音量能力"
                } else {
                    "控制器运行中，等待无障碍服务连接"
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
                logicalPosition = mappingState?.logicalPosition,
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
                statusMessage = "正在重新探测音量能力",
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
                statusMessage = if (command.running) statusMessage else "前台控制器已停止",
            )
        }
    }

    private fun handleAccessibilityChanged(command: Command.AccessibilityChanged) {
        if (!adoptTransition(command.stamp)) return
        publish {
            copy(
                isAccessibilityConnected = command.connected,
                statusMessage = when {
                    command.connected && isForegroundServiceRunning -> "映射服务已就绪"
                    command.connected -> "无障碍已连接，请启动控制器"
                    else -> "请在系统设置中启用音量键映射服务"
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
        reducer = VolumeMappingReducer(settings.outputCurve, settings.keyConfig)
        mappingState = null
        publish {
            copy(
                logicalPosition = null,
                expectedIndex = snapshot?.currentIndex,
                statusMessage = if (canInterceptKeys) "映射曲线已更新" else statusMessage,
            )
        }
    }

    private fun handleKeyDown(command: Command.KeyDown) {
        if (!isGestureStartCurrent(command)) return

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
                    "读取媒体音量失败：${throwable.message ?: throwable.javaClass.simpleName}",
                    command.stamp,
                )
                startOwnerDrainWatchdog(command.token)
            }
            return
        }
        if (!isGestureStartCurrent(command)) return

        if (!acceptSnapshot(observedSnapshot, command.stamp, allowRouteChange = false)) return
        if (!isGestureStartCurrent(command) || backend.isVolumeFixed) {
            startOwnerDrainWatchdog(command.token)
            return
        }

        val observedNormalized = VolumeQuantizer.normalizedForIndex(
            observedSnapshot.currentIndex,
            observedSnapshot.range,
            settings.quantizationMode,
        )
        val canKeepSubStepRemainder = canKeepLogicalRemainder(
            mappingState = mappingState,
            previousExpectedIndex = previousExpected,
            previousSnapshot = previousSnapshot,
            observedSnapshot = observedSnapshot,
        )

        val initial = if (canKeepSubStepRemainder) {
            checkNotNull(mappingState)
        } else {
            reducer.initialState(observedNormalized)
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
        )
        mappingState = reduction.state
        activeGesture = gesture
        lastEnvironmentGuardAtMillis = SystemClock.uptimeMillis()
        publish { copy(logicalPosition = reduction.state.logicalPosition) }

        if (reduction.writeRequested) {
            applyTarget(reduction.targetOutputVolume, force = true, context = gesture.writeContext)
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
        pendingWrite = null
        publish { copy(logicalPosition = reduction.state.logicalPosition) }

        // A final UP integration must not depend on another ticker to flush a throttled target.
        if (reduction.writeRequested) {
            applyTarget(reduction.targetOutputVolume, force = true, context = gesture.writeContext)
        }
    }

    private fun handleTick(command: Command.Tick) {
        val gesture = activeGesture ?: return
        if (gesture.token != command.token || gesture.stamp != command.stamp) return
        if (!isActiveGestureCurrent(gesture)) {
            cancelActiveGesture(resetMapping = false)
            return
        }
        if (!guardActiveEnvironment(gesture, command.nowMillis)) return

        val state = mappingState ?: return
        val reduction = reducer.reduce(state, VolumeMappingAction.AdvanceTime(command.nowMillis))
        mappingState = reduction.state
        publish { copy(logicalPosition = reduction.state.logicalPosition) }
        if (reduction.writeRequested) {
            applyTarget(reduction.targetOutputVolume, force = false, context = gesture.writeContext)
        }

        val pending = pendingWrite
        if (
            pending != null &&
            pending.context == gesture.writeContext &&
            isWriteIntervalElapsed(command.nowMillis)
        ) {
            pendingWrite = null
            writeTarget(pending.targetNormalized, pending.context)
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
        publish { copy(statusMessage = "音量键 UP 超时，已停止本次连续调整") }
    }

    private fun handleOwnerDrainExpired(command: Command.OwnerDrainExpired) {
        val currentOwner = gestureOwner.get()
        if (currentOwner != null && currentOwner != command.token) return
        if (activeGesture?.token == command.token) {
            cancelActiveGesture(resetMapping = false)
        }
        publish { copy(statusMessage = "已清理未收到 UP 的旧音量键手势") }
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
                statusMessage = "音频环境已变化，正在重新探测",
            )
        }
        refreshSnapshotInternal(command.stamp, allowRouteChange = true)
    }

    private fun handleRefreshSnapshot(command: Command.RefreshSnapshot) {
        if (!adoptTransition(command.stamp)) return
        mappingState = null
        refreshSnapshotInternal(command.stamp, allowRouteChange = true)
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
                    "连续调整时无法核对输出路由：${it.message ?: it.javaClass.simpleName}",
                    gesture.stamp,
                )
            }
            return false
        }
        if (!isActiveGestureCurrent(gesture)) return false

        val currentSnapshot = _runtime.value.snapshot ?: return false
        if (
            guardedSnapshot.route.stableId != gesture.routeId ||
            guardedSnapshot.range != currentSnapshot.range
        ) {
            adoptUnexpectedRoute(guardedSnapshot)
            return false
        }
        if (backend.isVolumeFixed) {
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
                statusMessage = "当前通话或系统音频场景不接管音量键",
            )
        }
    }

    private fun invalidateForFixedVolume(snapshot: RouteVolumeSnapshot) {
        val stamp = beginControlTransition()
        actorControlEpoch = stamp.controlEpoch
        cancelActorWork(resetMapping = false)
        publish {
            copy(
                snapshot = snapshot,
                isVolumeFixed = true,
                expectedIndex = snapshot.currentIndex,
                statusMessage = "系统报告固定音量，已交还默认按键行为",
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
                    "当前通话或系统音频场景不接管音量键"
                } else {
                    statusMessage
                },
            )
        }
        if (!mediaContextSafe) return

        val snapshot = backend.snapshot().getOrElse {
            if (isStampCurrent(stamp)) {
                registerFailure(
                    "音量能力探测失败：${it.message ?: it.javaClass.simpleName}",
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
        val previousRouteId = _runtime.value.snapshot?.route?.stableId
        if (
            !allowRouteChange &&
            previousRouteId != null &&
            previousRouteId != snapshot.route.stableId
        ) {
            adoptUnexpectedRoute(snapshot)
            return false
        }

        publish {
            copy(
                snapshot = snapshot,
                isVolumeFixed = backend.isVolumeFixed,
                isMediaContextSafe = backend.isMediaContextSafe,
                expectedIndex = snapshot.currentIndex,
                statusMessage = when {
                    backend.isVolumeFixed -> "系统报告固定音量，已交还默认按键行为"
                    !backend.isMediaContextSafe -> "当前通话或系统音频场景不接管音量键"
                    isFailOpen -> statusMessage
                    canInterceptKeys -> "映射服务已就绪"
                    else -> statusMessage
                },
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
                isVolumeFixed = backend.isVolumeFixed,
                isMediaContextSafe = backend.isMediaContextSafe,
                logicalPosition = null,
                expectedIndex = snapshot.currentIndex,
                statusMessage = "检测到输出路由切换，已取消本次按键手势",
            )
        }
    }

    private fun applyTarget(
        normalizedTarget: Double,
        force: Boolean,
        context: WriteContext,
    ) {
        if (!isWriteContextCurrent(context)) return
        val now = SystemClock.uptimeMillis()
        if (!force && !isWriteIntervalElapsed(now)) {
            pendingWrite = PendingWrite(normalizedTarget, context)
            return
        }
        pendingWrite = null
        writeTarget(normalizedTarget, context)
    }

    private fun writeTarget(normalizedTarget: Double, context: WriteContext) {
        if (!isWriteContextCurrent(context)) return
        val snapshot = _runtime.value.snapshot ?: return
        if (snapshot.route.stableId != context.routeId) return

        val quantized = VolumeQuantizer.quantize(
            targetNormalized = normalizedTarget,
            range = snapshot.range,
            mode = settings.quantizationMode,
        )
        if (quantized.index == _runtime.value.expectedIndex) return

        // Epoch/route check immediately before the blocking write.
        if (!isWriteContextCurrent(context)) return
        lastWriteAtMillis = SystemClock.uptimeMillis()
        val result = backend.setMediaVolume(quantized.index, settings.showSystemVolumeUi)
        // A transition racing the Binder call makes its result stale, regardless of success/failure.
        if (!isWriteContextCurrent(context)) return

        result.onSuccess {
            publish {
                copy(
                    expectedIndex = quantized.index,
                    statusMessage = "已请求 ${quantized.index}/${snapshot.range.maxIndex}",
                )
            }
            scheduleVerification(context, quantized.index, lastWriteAtMillis)
        }.onFailure {
            registerFailure(
                "系统拒绝修改音量：${it.message ?: it.javaClass.simpleName}",
                context.stamp,
            )
        }
    }

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
                registerFailure("写后无法读取媒体音量", cycle.context.stamp)
            }
            return
        }
        if (!isWriteContextCurrent(cycle.context) || verificationCycle?.id != cycle.id) return
        if (observed.route.stableId != cycle.context.routeId) {
            adoptUnexpectedRoute(observed)
            return
        }
        if (backend.isVolumeFixed) {
            invalidateForFixedVolume(observed)
            return
        }

        val targetIndex = cycle.latestTargetIndex
        publish {
            copy(
                snapshot = observed,
                isVolumeFixed = backend.isVolumeFixed,
                isMediaContextSafe = backend.isMediaContextSafe,
                expectedIndex = observed.currentIndex,
            )
        }
        if (observed.currentIndex == targetIndex) {
            cancelVerification()
            publish {
                copy(
                    consecutiveWriteFailures = 0,
                    statusMessage = "写入已确认：${observed.currentIndex}/${observed.range.maxIndex}",
                )
            }
        } else {
            synchronizeMappingToObserved(observed)
            if (!command.final) return

            val latestWriteAge = SystemClock.uptimeMillis() - cycle.latestWriteAtMillis
            if (latestWriteAge < FINAL_VERIFY_DELAY_MILLIS) {
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
                return
            }
            cancelVerification()
            registerFailure(
                "写后回读不一致：请求 $targetIndex，系统保持 ${observed.currentIndex}",
                cycle.context.stamp,
            )
            if (!isStampCurrent(cycle.context.stamp)) return
        }
    }

    private fun synchronizeMappingToObserved(observed: RouteVolumeSnapshot) {
        val observedNormalized = VolumeQuantizer.normalizedForIndex(
            observed.currentIndex,
            observed.range,
            settings.quantizationMode,
        )
        val state = mappingState
        mappingState = if (state == null) {
            reducer.initialState(observedNormalized)
        } else {
            reducer.reduce(
                state,
                VolumeMappingAction.SynchronizeObserved(
                    outputVolume = observedNormalized,
                    forceWhilePressed = true,
                ),
            ).state
        }
        publish { copy(logicalPosition = mappingState?.logicalPosition) }
    }

    private fun registerFailure(message: String, stamp: EpochStamp) {
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
                statusMessage = if (failOpen) "$message；已自动放行后续音量键" else message,
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
        pendingWrite = null
        lastWriteAtMillis = Long.MIN_VALUE
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
        pendingWrite = null
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
                delay(TICK_INTERVAL_MILLIS)
                if (gestureOwner.get() != gesture.token) break

                val now = SystemClock.uptimeMillis()
                if (now - ownerHeartbeatAtMillis.get() >= LOST_UP_WATCHDOG_MILLIS) {
                    commands.send(Command.WatchdogExpired(gesture.token, gesture.stamp, now))
                    break
                }
                // Ticks are coalescible: reducer integration uses absolute event time.
                commands.trySend(Command.Tick(gesture.token, gesture.stamp, now))
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

    private fun isWriteIntervalElapsed(nowMillis: Long): Boolean =
        lastWriteAtMillis == Long.MIN_VALUE ||
            nowMillis - lastWriteAtMillis >= MIN_WRITE_INTERVAL_MILLIS

    private fun isStampCurrent(stamp: EpochStamp): Boolean =
        actorControlEpoch == stamp.controlEpoch &&
            controlEpoch.get() == stamp.controlEpoch &&
            routeEpoch.get() == stamp.routeEpoch

    private fun isGestureStartCurrent(command: Command.KeyDown): Boolean =
        // A fast UP can drain callback ownership before the actor receives this already-accepted
        // DOWN. Channel ordering still delivers DOWN before UP, so epoch/eligibility determines
        // whether the tap is current; gestureOwner only governs continued hold/ticker ownership.
        isWriteContextCurrent(
            WriteContext(
                stamp = command.stamp,
                routeId = _runtime.value.snapshot?.route?.stableId.orEmpty(),
            ),
            requireRouteId = false,
        )

    private fun isActiveGestureCurrent(gesture: ActiveGesture): Boolean =
        activeGesture == gesture &&
            gestureOwner.get() == gesture.token &&
            isWriteContextCurrent(gesture.writeContext)

    private fun isWriteContextCurrent(
        context: WriteContext,
        requireRouteId: Boolean = true,
    ): Boolean {
        if (!isStampCurrent(context.stamp)) return false
        if (!eligible.get() || !backend.isMediaContextSafe) return false
        if (requireRouteId && _runtime.value.snapshot?.route?.stableId != context.routeId) return false
        return true
    }

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
    )

    private data class ActiveGesture(
        val token: KeyToken,
        val direction: VolumeDirection,
        val stamp: EpochStamp,
        val routeId: String,
    ) {
        val writeContext: WriteContext
            get() = WriteContext(stamp, routeId)
    }

    private data class PendingWrite(
        val targetNormalized: Double,
        val context: WriteContext,
    )

    private data class VerificationCycle(
        val id: Long,
        val context: WriteContext,
        var latestTargetIndex: Int,
        var latestWriteAtMillis: Long,
    )

    private sealed interface Command {
        data class Arm(val stamp: EpochStamp) : Command
        data class Disarm(val reason: String, val stamp: EpochStamp) : Command
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

        data class EnvironmentChanged(val stamp: EpochStamp) : Command
        data class RefreshSnapshot(val stamp: EpochStamp) : Command
        data class VerifyWrite(val cycleId: Long, val final: Boolean) : Command
    }

    private companion object {
        const val COMMAND_BUFFER_CAPACITY = 64
        const val TICK_INTERVAL_MILLIS = 50L
        const val LOST_UP_WATCHDOG_MILLIS = 2_000L
        const val ENVIRONMENT_GUARD_INTERVAL_MILLIS = 500L
        const val MIN_WRITE_INTERVAL_MILLIS = 70L
        const val FIRST_VERIFY_DELAY_MILLIS = 80L
        const val FINAL_VERIFY_DELAY_MILLIS = 380L
        const val FAILURE_LIMIT = 3

        fun maxLong(left: Long, right: Long): Long = maxOf(left, right)
    }
}
