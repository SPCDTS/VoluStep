package dev.spcdts.volumemapper.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.spcdts.volumemapper.core.AbsoluteVolumeSupport
import dev.spcdts.volumemapper.core.AudioRouteDescriptor
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.RouteConfidence
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.max

/**
 * 只使用公开 AudioManager API 的媒体音量后端。
 *
 * 厂商差异通过运行时读取 min/max/dB 表以及写后回读处理，不硬编码“小米 150 档”之类的
 * 品牌规则。耳机实际声压和 AVRCP/VCS 原始值不属于公开 API 能力。
 */
class AudioManagerVolumeBackend(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mediaRouter = context.getSystemService(MediaRouter::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val mediaContextSafe = AtomicBoolean(
        audioManager.mode == AudioManager.MODE_NORMAL,
    )
    private val rangeCache = ConcurrentHashMap<RangeCacheKey, RouteVolumeRange>()
    private val routeRangeKeys = ConcurrentHashMap<RouteCacheKey, RangeCacheKey>()
    private val rangeCacheGeneration = AtomicLong(0L)

    val isVolumeFixed: Boolean
        get() = audioManager.isVolumeFixed

    val isMediaContextSafe: Boolean
        get() = mediaContextSafe.get()

    /** Refreshes the callback-safe media-context cache outside AccessibilityService.onKeyEvent. */
    fun refreshMediaContextSafety(): Boolean {
        val safe = audioManager.mode == AudioManager.MODE_NORMAL
        mediaContextSafe.set(safe)
        return safe
    }

    fun snapshot(): Result<RouteVolumeSnapshot> = runCatching {
        val resolvedRoute = resolveOutputDevice()
        val device = resolvedRoute.device
        val descriptor = device.toDescriptor(resolvedRoute.confidence)
        val observedIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val range = cachedOrReadRange(descriptor, device.type, observedIndex)
        val currentIndex = observedIndex.coerceIn(range.minIndex, range.maxIndex)

        RouteVolumeSnapshot(
            route = descriptor,
            range = range,
            currentIndex = currentIndex,
            observedAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

    fun setMediaVolume(index: Int, showSystemUi: Boolean): Result<Unit> = runCatching {
        check(!isVolumeFixed) { "该设备报告固定音量，系统不允许应用调整" }
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, flags)
    }

    /**
     * Emits when device topology, the selected legacy media route, or audio mode changes.
     * Every signal invalidates route-dependent capability data before it reaches consumers.
     */
    fun environmentChanges(): Flow<Unit> = callbackFlow {
        fun signalEnvironmentChanged(mode: Int? = null) {
            if (mode != null) mediaContextSafe.set(mode == AudioManager.MODE_NORMAL)
            invalidateRangeCache()
            trySend(Unit)
        }

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                signalEnvironmentChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                signalEnvironmentChanged()
            }
        }
        val mediaRouterCallback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(
                router: MediaRouter,
                type: Int,
                info: MediaRouter.RouteInfo,
            ) {
                signalEnvironmentChanged()
            }

            override fun onRouteUnselected(
                router: MediaRouter,
                type: Int,
                info: MediaRouter.RouteInfo,
            ) {
                signalEnvironmentChanged()
            }

            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
                signalEnvironmentChanged()
            }
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        mainHandler.post {
            mediaRouter.addCallback(
                MediaRouter.ROUTE_TYPE_LIVE_AUDIO,
                mediaRouterCallback,
                0,
            )
        }

        val unregisterModeListener = if (Build.VERSION.SDK_INT >= 31) {
            val listener = AudioManager.OnModeChangedListener { mode ->
                signalEnvironmentChanged(mode)
            }
            audioManager.addOnModeChangedListener(applicationContext.mainExecutor, listener)
            refreshMediaContextSafety()
            val unregister: () -> Unit = {
                audioManager.removeOnModeChangedListener(listener)
            }
            unregister
        } else {
            null
        }

        awaitClose {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            unregisterModeListener?.invoke()
            mainHandler.post { mediaRouter.removeCallback(mediaRouterCallback) }
        }
    }.conflate()

    /** Compatibility alias for callers being migrated to [environmentChanges]. */
    @Deprecated("Use environmentChanges()")
    fun routeChanges(): Flow<Unit> = environmentChanges()

    private fun resolveOutputDevice(): ResolvedOutputDevice {
        val routed = if (Build.VERSION.SDK_INT >= 33) {
            audioManager.getAudioDevicesForAttributes(mediaAttributes).distinctBy { it.id }
        } else {
            emptyList()
        }
        if (routed.size == 1) {
            return ResolvedOutputDevice(routed.single(), RouteConfidence.CONFIRMED)
        }
        if (routed.size > 1) {
            return ResolvedOutputDevice(
                device = routed.maxBy { routePriority(it.type) },
                confidence = RouteConfidence.HEURISTIC,
            )
        }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val selectedLegacyDeviceType = mediaRouter
            .getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            ?.deviceType
        val selectedRouteOutputs = selectedLegacyDeviceType
            ?.let { deviceType ->
                outputs.filter { output -> matchesLegacyRoute(deviceType, output.type) }
            }
            .orEmpty()
        val candidates = selectedRouteOutputs.ifEmpty { outputs }
        val device = candidates.maxByOrNull { routePriority(it.type) }
            ?: error("系统没有报告媒体输出设备")
        return ResolvedOutputDevice(device, RouteConfidence.HEURISTIC)
    }

    // These are integer comparisons only. Older releases cannot report the newer device-type
    // values, while inlining the constants keeps this fallback exhaustive on newer releases.
    @SuppressLint("InlinedApi")
    private fun matchesLegacyRoute(mediaRouterDeviceType: Int, audioDeviceType: Int): Boolean =
        when (mediaRouterDeviceType) {
            MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> audioDeviceType in setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )

            MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER ->
                audioDeviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

            MediaRouter.RouteInfo.DEVICE_TYPE_TV -> audioDeviceType in setOf(
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC,
            )

            else -> false
        }

    private fun cachedOrReadRange(
        descriptor: AudioRouteDescriptor,
        deviceType: Int,
        observedIndex: Int,
    ): RouteVolumeRange {
        val routeKey = RouteCacheKey(descriptor.stableId, descriptor.confidence)
        val generation = rangeCacheGeneration.get()
        val cached = routeRangeKeys[routeKey]
            ?.takeIf { it.generation == generation }
            ?.let(rangeCache::get)
        if (cached != null && cached.contains(observedIndex)) return cached

        val minIndex = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cacheKey = RangeCacheKey(
            routeStableId = descriptor.stableId,
            minIndex = minIndex,
            maxIndex = maxIndex,
            confidence = descriptor.confidence,
            generation = generation,
        )
        val range = rangeCache.computeIfAbsent(cacheKey) {
            RouteVolumeRange(
                minIndex = minIndex,
                maxIndex = maxIndex,
                // A guessed device type can return a valid-looking but wrong dB table. Keep the
                // Keep the table empty: an ambiguous device would make diagnostic dB data lie.
                decibelsByIndex = if (descriptor.confidence == RouteConfidence.CONFIRMED) {
                    readMonotonicDbTable(minIndex, maxIndex, deviceType)
                } else {
                    emptyList()
                },
            )
        }
        if (rangeCacheGeneration.get() == generation) {
            routeRangeKeys[routeKey] = cacheKey
        } else {
            // An environment callback raced the dB scan. Never make that stale table reachable.
            rangeCache.remove(cacheKey, range)
        }
        return range
    }

    private fun invalidateRangeCache() {
        rangeCacheGeneration.incrementAndGet()
        routeRangeKeys.clear()
        rangeCache.clear()
    }

    private fun readMonotonicDbTable(
        minIndex: Int,
        maxIndex: Int,
        deviceType: Int,
    ): List<Double?> {
        var previous: Double? = null
        return (minIndex..maxIndex).map { index ->
            val raw = runCatching {
                audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType)
                    .toDouble()
            }.getOrNull()
            val finite = raw?.takeIf { it.isFinite() }
            val sanitized = when {
                finite == null -> null
                previous == null -> finite
                else -> max(previous, finite)
            }
            if (sanitized != null) previous = sanitized
            sanitized
        }
    }

    private fun AudioDeviceInfo.toDescriptor(confidence: RouteConfidence): AudioRouteDescriptor {
        val routeType = type.toRouteType()
        val label = productName?.toString()?.takeIf { it.isNotBlank() }
        return AudioRouteDescriptor(
            stableId = "${routeType.name}:$id:${label ?: type}",
            type = routeType,
            productName = label,
            absoluteVolumeSupport = when (routeType) {
                AudioRouteType.BLUETOOTH_A2DP,
                AudioRouteType.BLUETOOTH_LE,
                -> AbsoluteVolumeSupport.UNKNOWN
                else -> AbsoluteVolumeSupport.UNSUPPORTED
            },
            confidence = confidence,
        )
    }

    @SuppressLint("InlinedApi")
    private fun Int.toRouteType(): AudioRouteType = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        -> AudioRouteType.BUILT_IN_SPEAKER

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        -> AudioRouteType.WIRED_HEADSET

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRouteType.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> AudioRouteType.BLUETOOTH_LE

        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioRouteType.USB

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        -> AudioRouteType.HDMI

        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> AudioRouteType.REMOTE
        else -> AudioRouteType.UNKNOWN
    }

    private fun routePriority(type: Int): Int = when (type.toRouteType()) {
        AudioRouteType.BLUETOOTH_LE -> 80
        AudioRouteType.BLUETOOTH_A2DP -> 70
        AudioRouteType.USB -> 60
        AudioRouteType.WIRED_HEADSET -> 50
        AudioRouteType.HDMI -> 40
        AudioRouteType.REMOTE -> 30
        AudioRouteType.BUILT_IN_SPEAKER -> 20
        AudioRouteType.UNKNOWN -> 0
    }

    private data class ResolvedOutputDevice(
        val device: AudioDeviceInfo,
        val confidence: RouteConfidence,
    )

    private data class RangeCacheKey(
        val routeStableId: String,
        val minIndex: Int,
        val maxIndex: Int,
        val confidence: RouteConfidence,
        val generation: Long,
    )

    private data class RouteCacheKey(
        val routeStableId: String,
        val confidence: RouteConfidence,
    )
}
