package dev.spcdts.volumemapper.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.spcdts.volumemapper.core.KeyMappingConfig
import dev.spcdts.volumemapper.core.MappingCurve
import dev.spcdts.volumemapper.core.MappingPoint
import dev.spcdts.volumemapper.core.StepVolumeMap
import java.io.IOException
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private val Context.volumeMapperDataStore by preferencesDataStore(name = "volume_mapper")

data class VolumeMapperSettings(
    val outputMap: StepVolumeMap = StepVolumeMap(
        basisSpan = DEFAULT_OUTPUT_BASIS_SPAN,
        pressCount = DEFAULT_OUTPUT_PRESS_COUNT,
        normalizedXs = listOf(0.0, 0.12, 0.34, 0.70, 1.0),
        offsets = listOf(0, 1, 5, 16, DEFAULT_OUTPUT_BASIS_SPAN),
    ),
    val keyConfig: KeyMappingConfig = KeyMappingConfig(
        holdDelayMillis = 350L,
        holdStepIntervalMillis = KeyMappingConfig.DEFAULT_HOLD_STEP_INTERVAL_MILLIS,
    ),
    val showSystemVolumeUi: Boolean = true,
    val disclosureAccepted: Boolean = false,
)

data class SettingsRepositoryState(
    val settings: VolumeMapperSettings,
    val initialSettingsLoaded: Boolean,
)

private const val DEFAULT_OUTPUT_BASIS_SPAN = 30
private const val DEFAULT_OUTPUT_PRESS_COUNT = 18
private const val LEGACY_OUTPUT_BASIS_SPAN = 150

/** 单进程设置仓库。内存状态立即更新，DataStore 写入做短暂防抖以适配拖动曲线。 */
class SettingsRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val dataStore = context.volumeMapperDataStore
    private val _settings = MutableStateFlow(VolumeMapperSettings())
    val settings: StateFlow<VolumeMapperSettings> = _settings
    private val _state = MutableStateFlow(
        SettingsRepositoryState(
            settings = _settings.value,
            initialSettingsLoaded = false,
        ),
    )
    val state: StateFlow<SettingsRepositoryState> = _state
    private val stateLock = Any()
    private val pendingTransforms = ArrayDeque<(VolumeMapperSettings) -> VolumeMapperSettings>()
    private val pendingWrites = Channel<SettingsWriteRequest>(Channel.UNLIMITED)
    private var isLoaded = false

    /** 供仪器测试等待 DataStore 初始值合并完成，避免把默认占位状态误当成用户配置。 */
    internal val hasLoadedInitialSettings: Boolean
        get() = _state.value.initialSettingsLoaded

    init {
        val writerJob = scope.launch { collectPendingWrites() }
        writerJob.invokeOnCompletion { cause ->
            val failure = cause ?: CancellationException("Settings writer stopped")
            pendingWrites.close(failure)
            while (true) {
                val request = pendingWrites.tryReceive().getOrNull() ?: break
                request.completion?.completeExceptionally(failure)
            }
        }

        scope.launch {
            val loaded = try {
                dataStore.data
                    .catch { throwable ->
                        if (throwable is IOException) {
                            emit(androidx.datastore.preferences.core.emptyPreferences())
                        } else {
                            throw throwable
                        }
                    }
                    .first()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException || throwable !is Exception) throw throwable
                // Stay operational with defaults after a one-off non-cancellation read failure.
                androidx.datastore.preferences.core.emptyPreferences()
            }
            val loadedSettings = SettingsSerialization.decode(loaded)
            synchronized(stateLock) {
                var merged = loadedSettings
                pendingTransforms.forEach { transform -> merged = transform(merged) }
                val shouldPersistMergedSettings = pendingTransforms.isNotEmpty()
                pendingTransforms.clear()
                isLoaded = true
                publishSettingsLocked(merged)
                if (shouldPersistMergedSettings) {
                    // The merged snapshot and its FIFO position form one linearized transition.
                    enqueueLocked(SettingsWriteRequest(merged, immediate = false))
                }
            }
        }
    }

    fun updateOutputMap(outputMap: StepVolumeMap) = update { copy(outputMap = outputMap) }

    fun updateKeyConfig(config: KeyMappingConfig) = update { copy(keyConfig = config) }

    fun updateShowSystemUi(show: Boolean) = update { copy(showSystemVolumeUi = show) }

    fun acceptDisclosure() = update { copy(disclosureAccepted = true) }

    /** 绕过拖动防抖并立即入队；此非挂起 API 返回时不保证 DataStore 已经落盘。 */
    fun flushPendingWrite() {
        synchronized(stateLock) {
            if (isLoaded) {
                enqueueLocked(SettingsWriteRequest(_settings.value, immediate = true))
            }
        }
    }

    /**
     * 仪器测试使用：原子替换完整设置，并等待非丢失的串行写入 actor 确认 DataStore 落盘。
     * 回执带超时，应用作用域异常终止时也不会让测试清理永久挂起。
     */
    internal suspend fun replaceSettingsAndAwait(settings: VolumeMapperSettings) {
        val completion = CompletableDeferred<Unit>()
        synchronized(stateLock) {
            check(isLoaded) { "Initial settings have not loaded" }
            publishSettingsLocked(settings)
            enqueueLocked(
                SettingsWriteRequest(
                    settings = settings,
                    immediate = true,
                    completion = completion,
                ),
            )
        }
        withTimeout(TEST_PERSISTENCE_TIMEOUT_MILLIS) {
            completion.await()
        }
    }

    private fun update(transform: VolumeMapperSettings.() -> VolumeMapperSettings) {
        synchronized(stateLock) {
            val updated = _settings.value.transform()
            publishSettingsLocked(updated)
            if (isLoaded) {
                enqueueLocked(SettingsWriteRequest(updated, immediate = false))
            } else {
                pendingTransforms.addLast { current -> current.transform() }
            }
        }
    }

    /**
     * Debounces ordinary drag updates but never drops an immediate/acknowledged request. All sends
     * happen while [stateLock] is held, so channel order is identical to in-memory state order.
     */
    private suspend fun collectPendingWrites() {
        collectSettingsWriteRequests(
            pendingWrites = pendingWrites,
            debounceMillis = WRITE_DEBOUNCE_MILLIS,
        ) { request ->
            dataStore.edit { preferences ->
                SettingsSerialization.encode(preferences, request.settings)
            }
        }
    }

    /** Must be called while [stateLock] is held. */
    private fun enqueueLocked(request: SettingsWriteRequest) {
        check(Thread.holdsLock(stateLock)) { "Settings writes must be enqueued under stateLock" }
        check(request.completion == null || request.immediate) {
            "Acknowledged settings writes must be immediate"
        }
        val result = pendingWrites.trySend(request)
        if (result.isFailure) {
            request.completion?.completeExceptionally(
                result.exceptionOrNull() ?: IllegalStateException("Settings writer is closed"),
            )
        }
    }

    /** Must be called while [stateLock] is held. UI observes both fields through one snapshot. */
    private fun publishSettingsLocked(settings: VolumeMapperSettings) {
        check(Thread.holdsLock(stateLock)) { "Settings state must be published under stateLock" }
        _settings.value = settings
        _state.value = SettingsRepositoryState(
            settings = settings,
            initialSettingsLoaded = isLoaded,
        )
    }

    private companion object {
        const val WRITE_DEBOUNCE_MILLIS = 180L
        const val TEST_PERSISTENCE_TIMEOUT_MILLIS = 10_000L
    }
}

internal data class SettingsWriteRequest(
    val settings: VolumeMapperSettings,
    val immediate: Boolean,
    val completion: CompletableDeferred<Unit>? = null,
    val enqueuedAtMillis: Long = monotonicSettingsTimeMillis(),
)

/**
 * Ordinary full-state snapshots use an enqueue-time trailing-edge debounce: every later ordinary
 * request resets the deadline. An immediate request cuts the window short and is persisted in FIFO
 * order. Only the selected request can carry an acknowledgement, which completes after persistence.
 */
internal suspend fun collectSettingsWriteRequests(
    pendingWrites: ReceiveChannel<SettingsWriteRequest>,
    debounceMillis: Long,
    persist: suspend (SettingsWriteRequest) -> Unit,
) {
    require(debounceMillis >= 0L) { "debounceMillis must not be negative" }
    while (true) {
        var request = pendingWrites.receive()
        if (!request.immediate) {
            while (true) {
                val alreadyQueued = pendingWrites.tryReceive().getOrNull()
                if (alreadyQueued != null) {
                    request = alreadyQueued
                    if (request.immediate) break
                    continue
                }

                val elapsedMillis = (monotonicSettingsTimeMillis() - request.enqueuedAtMillis)
                    .coerceAtLeast(0L)
                val remainingMillis = debounceMillis - elapsedMillis
                if (remainingMillis <= 0L) break
                val newer = withTimeoutOrNull(remainingMillis) {
                    pendingWrites.receive()
                } ?: break
                request = newer
                if (request.immediate) break
            }
        }

        try {
            persist(request)
            request.completion?.complete(Unit)
        } catch (throwable: Throwable) {
            request.completion?.completeExceptionally(throwable)
            if (throwable is CancellationException || throwable !is Exception) throw throwable
            // A transient DataStore failure rejects only this acknowledged request. The actor
            // remains alive, and a later full-state snapshot can persist the latest settings.
        }
    }
}

private fun monotonicSettingsTimeMillis(): Long = System.nanoTime() / 1_000_000L

/** 可独立单测的 Preferences 编解码器；每个字段单独校验，单项损坏不会清空其他设置。 */
internal object SettingsSerialization {
    fun decode(preferences: Preferences): VolumeMapperSettings {
        val defaults = VolumeMapperSettings()
        val defaultKeyConfig = defaults.keyConfig
        return defaults.copy(
            outputMap = preferences.readStepVolumeMap()
                ?: migrateLegacyOutputMap(preferences, defaults.outputMap),
            keyConfig = KeyMappingConfig(
                holdDelayMillis = preferences.readLong(Keys.HOLD_DELAY)
                    ?.takeIf { it >= 0L }
                    ?: defaultKeyConfig.holdDelayMillis,
                holdStepIntervalMillis = normalizePersistedHoldStepInterval(
                    persistedValue = preferences.readLong(Keys.HOLD_STEP_INTERVAL),
                    defaultValue = defaultKeyConfig.holdStepIntervalMillis,
                ),
            ),
            showSystemVolumeUi = preferences.readBoolean(Keys.SHOW_SYSTEM_UI)
                ?: defaults.showSystemVolumeUi,
            disclosureAccepted = preferences.readBoolean(Keys.DISCLOSURE_ACCEPTED)
                ?: defaults.disclosureAccepted,
        )
    }

    fun encode(
        preferences: MutablePreferences,
        settings: VolumeMapperSettings,
    ) {
        preferences[Keys.OUTPUT_STEP_MAP] = encodeStepVolumeMap(settings.outputMap)
        // Keep a current v2 shadow payload so a downgraded build can still read the authored shape.
        preferences[Keys.OUTPUT_CURVE] = encodeCurve(settings.outputMap.toLegacyCurve())
        preferences[Keys.TAP_STEP] = settings.outputMap.toLegacyTapStep()
        preferences[Keys.HOLD_DELAY] = settings.keyConfig.holdDelayMillis
        preferences[Keys.HOLD_STEP_INTERVAL] = settings.keyConfig.holdStepIntervalMillis
        // These fields belonged to the superseded ramp-based hold model. Remove stale values so a
        // later write cannot look as though the fixed interval is still affected by hidden tuning.
        preferences.remove(Keys.HOLD_SPEED)
        preferences.remove(Keys.RAMP_DURATION)
        preferences.remove(Keys.RAMP_MULTIPLIER)
        preferences.remove(Keys.HOLD_CURVE)
        preferences[Keys.QUANTIZATION_MODE_NAME] = "INDEX"
        preferences.remove(Keys.LEGACY_QUANTIZATION_MODE_ORDINAL)
        preferences[Keys.SHOW_SYSTEM_UI] = settings.showSystemVolumeUi
        preferences[Keys.DISCLOSURE_ACCEPTED] = settings.disclosureAccepted
    }

    fun encodeCurve(curve: MappingCurve): String = buildString {
        append(CURVE_FORMAT_V2)
        append('|')
        append(curve.minimumXSpacing)
        append('|')
        append(curve.points.joinToString(separator = ";") { "${it.x},${it.y}" })
    }

    fun decodeCurve(encoded: String): MappingCurve {
        val components = encoded.split('|', limit = 3)
        val (minimumXSpacing, encodedPoints) = if (
            components.size == 3 && components[0] == CURVE_FORMAT_V2
        ) {
            components[1].toDouble() to components[2]
        } else {
            MappingCurve.DEFAULT_MINIMUM_X_SPACING to encoded
        }
        return MappingCurve(
            points = encodedPoints.split(';').map { encodedPoint ->
                val parts = encodedPoint.split(',')
                require(parts.size == 2)
                MappingPoint(parts[0].toDouble(), parts[1].toDouble())
            },
            minimumXSpacing = minimumXSpacing,
        )
    }

    fun encodeStepVolumeMap(map: StepVolumeMap): String = buildString {
        append(STEP_MAP_FORMAT_V3)
        append('|')
        append(map.basisSpan)
        append('|')
        append(map.pressCount)
        append('|')
        append(
            map.normalizedXs.indices.joinToString(separator = ";") { index ->
                "${java.lang.Double.toString(map.normalizedXs[index])},${map.offsets[index]}"
            },
        )
    }

    fun decodeStepVolumeMap(encoded: String): StepVolumeMap {
        val components = encoded.split('|', limit = 4)
        return when (components.firstOrNull()) {
            STEP_MAP_FORMAT_V3 -> {
                require(components.size == 4)
                val points = components[3].split(';').map { encodedPoint ->
                    val parts = encodedPoint.split(',', limit = 2)
                    require(parts.size == 2)
                    parts[0].toDouble() to parts[1].toInt()
                }
                StepVolumeMap(
                    basisSpan = components[1].toInt(),
                    pressCount = components[2].toInt(),
                    normalizedXs = points.map { it.first },
                    offsets = points.map { it.second },
                )
            }
            STEP_MAP_FORMAT_V2 -> {
                require(components.size == 4)
                StepVolumeMap(
                    basisSpan = components[1].toInt(),
                    pressCount = components[2].toInt(),
                    offsets = components[3].split(',').map(String::toInt),
                )
            }
            STEP_MAP_FORMAT_V1 -> {
                require(components.size == 3)
                StepVolumeMap(
                    basisSpan = components[1].toInt(),
                    offsets = components[2].split(',').map(String::toInt),
                )
            }
            else -> throw IllegalArgumentException("Unsupported step-map format")
        }
    }

    private fun Preferences.readStepVolumeMap(): StepVolumeMap? =
        readString(Keys.OUTPUT_STEP_MAP)
            ?.let { encoded -> runCatching { decodeStepVolumeMap(encoded) }.getOrNull() }

    private fun migrateLegacyOutputMap(
        preferences: Preferences,
        default: StepVolumeMap,
    ): StepVolumeMap {
        val legacyCurve = preferences.readString(Keys.OUTPUT_CURVE)
            ?.let { encoded -> runCatching { decodeCurve(encoded) }.getOrNull() }
            ?: return default
        val legacyTapStep = preferences.readDouble(Keys.TAP_STEP)
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
        val pressCount = legacyTapStep
            ?.let { ceil(1.0 / it).toInt() }
            ?.coerceIn(1, LEGACY_OUTPUT_BASIS_SPAN)
            ?: default.pressCount
        return StepVolumeMap.fromCurve(
            curve = legacyCurve,
            basisSpan = LEGACY_OUTPUT_BASIS_SPAN,
            pressCount = pressCount,
            controlPointCount = minOf(
                legacyCurve.points.size,
                LEGACY_OUTPUT_BASIS_SPAN + 1,
            ),
        )
    }

    private fun StepVolumeMap.toLegacyCurve(): MappingCurve = MappingCurve(
        points = offsets.mapIndexed { index, offset ->
            MappingPoint(
                x = normalizedXs[index],
                y = offset.toDouble() / basisSpan.toDouble(),
            )
        },
        minimumXSpacing = minOf(
            MappingCurve.DEFAULT_MINIMUM_X_SPACING,
            normalizedXs.zipWithNext { left, right -> right - left }.min(),
        ),
    )

    /** Avoids an old build's ceil(1 / tapStep) rounding K upward after a downgrade. */
    private fun StepVolumeMap.toLegacyTapStep(): Double =
        if (pressCount == 1) 1.0 else Math.nextUp(1.0 / pressCount.toDouble())

    private fun Preferences.readBoolean(key: Preferences.Key<Boolean>): Boolean? =
        asMap()[key] as? Boolean

    private fun Preferences.readDouble(key: Preferences.Key<Double>): Double? =
        asMap()[key] as? Double

    private fun Preferences.readInt(key: Preferences.Key<Int>): Int? =
        asMap()[key] as? Int

    private fun Preferences.readLong(key: Preferences.Key<Long>): Long? =
        asMap()[key] as? Long

    private fun Preferences.readString(key: Preferences.Key<String>): String? =
        asMap()[key] as? String

    /** In-range legacy values snap to the nearest grid point; exact ties round upward. */
    private fun normalizePersistedHoldStepInterval(
        persistedValue: Long?,
        defaultValue: Long,
    ): Long {
        val value = persistedValue
            ?.takeIf {
                it in KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS..
                    KeyMappingConfig.MAX_HOLD_STEP_INTERVAL_MILLIS
            }
            ?: return defaultValue
        val grid = KeyMappingConfig.HOLD_STEP_INTERVAL_GRID_MILLIS
        val offset = value - KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS
        val roundedSteps = (offset + grid / 2L) / grid
        return KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS + roundedSteps * grid
    }

    private object Keys {
        val OUTPUT_STEP_MAP = stringPreferencesKey("output_step_map")
        val OUTPUT_CURVE = stringPreferencesKey("output_curve")
        val TAP_STEP = doublePreferencesKey("tap_step")
        val HOLD_DELAY = longPreferencesKey("hold_delay")
        val HOLD_STEP_INTERVAL = longPreferencesKey("hold_step_interval")
        // Kept only to remove obsolete values written by ramp-based releases.
        val HOLD_SPEED = doublePreferencesKey("hold_speed")
        val RAMP_DURATION = longPreferencesKey("ramp_duration")
        val RAMP_MULTIPLIER = doublePreferencesKey("ramp_multiplier")
        val HOLD_CURVE = stringPreferencesKey("hold_curve")
        val QUANTIZATION_MODE_NAME = stringPreferencesKey("quantization_mode_name")
        val LEGACY_QUANTIZATION_MODE_ORDINAL = intPreferencesKey("quantization_mode")
        val SHOW_SYSTEM_UI = booleanPreferencesKey("show_system_ui")
        val DISCLOSURE_ACCEPTED = booleanPreferencesKey("disclosure_accepted")
    }

    private const val CURVE_FORMAT_V2 = "v2"
    private const val STEP_MAP_FORMAT_V1 = "v1"
    private const val STEP_MAP_FORMAT_V2 = "v2"
    private const val STEP_MAP_FORMAT_V3 = "v3"
}
