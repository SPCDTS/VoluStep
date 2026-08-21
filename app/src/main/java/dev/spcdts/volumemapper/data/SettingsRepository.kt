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
import dev.spcdts.volumemapper.core.MappingPreset
import dev.spcdts.volumemapper.core.VolumeQuantizationMode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private val Context.volumeMapperDataStore by preferencesDataStore(name = "volume_mapper")

data class VolumeMapperSettings(
    val outputCurve: MappingCurve = MappingPreset.LOW_VOLUME_FINE.createCurve(),
    val keyConfig: KeyMappingConfig = KeyMappingConfig(
        tapStep = 0.02,
        holdDelayMillis = 350L,
        holdUnitsPerSecond = 0.10,
        holdRampDurationMillis = 1_500L,
        holdMaximumMultiplier = 4.0,
        holdRampCurve = MappingCurve.linear(),
    ),
    val quantizationMode: VolumeQuantizationMode = VolumeQuantizationMode.DECIBELS,
    val showSystemVolumeUi: Boolean = true,
    val disclosureAccepted: Boolean = false,
)

/** 单进程设置仓库。内存状态立即更新，DataStore 写入做短暂防抖以适配拖动曲线。 */
@OptIn(FlowPreview::class)
class SettingsRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val dataStore = context.volumeMapperDataStore
    private val _settings = MutableStateFlow(VolumeMapperSettings())
    val settings: StateFlow<VolumeMapperSettings> = _settings
    private val stateLock = Any()
    private val pendingTransforms = ArrayDeque<(VolumeMapperSettings) -> VolumeMapperSettings>()
    private val pendingWrites = Channel<PendingWrite>(Channel.CONFLATED)
    private var isLoaded = false

    init {
        scope.launch {
            val loaded = dataStore.data
                .catch { throwable ->
                    if (throwable is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
                    else throw throwable
                }
                .first()
            val loadedSettings = SettingsSerialization.decode(loaded)
            val mergedSettings: VolumeMapperSettings
            val shouldPersistMergedSettings: Boolean
            synchronized(stateLock) {
                var merged = loadedSettings
                pendingTransforms.forEach { transform -> merged = transform(merged) }
                shouldPersistMergedSettings = pendingTransforms.isNotEmpty()
                pendingTransforms.clear()
                isLoaded = true
                _settings.value = merged
                mergedSettings = merged
            }
            if (shouldPersistMergedSettings) {
                pendingWrites.trySend(PendingWrite(mergedSettings, immediate = false))
            }

            pendingWrites.receiveAsFlow()
                .debounce { request -> if (request.immediate) 0L else WRITE_DEBOUNCE_MILLIS }
                .collect { request ->
                    dataStore.edit { preferences ->
                        SettingsSerialization.encode(preferences, request.settings)
                    }
                }
        }
    }

    fun updateCurve(curve: MappingCurve) = update { copy(outputCurve = curve) }

    fun updateKeyConfig(config: KeyMappingConfig) = update { copy(keyConfig = config) }

    fun updateQuantizationMode(mode: VolumeQuantizationMode) =
        update { copy(quantizationMode = mode) }

    fun updateShowSystemUi(show: Boolean) = update { copy(showSystemVolumeUi = show) }

    fun acceptDisclosure() = update { copy(disclosureAccepted = true) }

    /** 绕过拖动防抖，把当前完整设置交给同一个串行写入流立即持久化。 */
    fun flushPendingWrite() {
        val current = synchronized(stateLock) {
            _settings.value.takeIf { isLoaded }
        }
        current?.let { pendingWrites.trySend(PendingWrite(it, immediate = true)) }
    }

    private fun update(transform: VolumeMapperSettings.() -> VolumeMapperSettings) {
        val valueToPersist = synchronized(stateLock) {
            val updated = _settings.value.transform()
            _settings.value = updated
            if (isLoaded) updated else {
                pendingTransforms.addLast { current -> current.transform() }
                null
            }
        }
        valueToPersist?.let {
            pendingWrites.trySend(PendingWrite(it, immediate = false))
        }
    }

    private data class PendingWrite(
        val settings: VolumeMapperSettings,
        val immediate: Boolean,
    )

    private companion object {
        const val WRITE_DEBOUNCE_MILLIS = 180L
    }
}

/** 可独立单测的 Preferences 编解码器；每个字段单独校验，单项损坏不会清空其他设置。 */
internal object SettingsSerialization {
    fun decode(preferences: Preferences): VolumeMapperSettings {
        val defaults = VolumeMapperSettings()
        val defaultKeyConfig = defaults.keyConfig
        return defaults.copy(
            outputCurve = preferences.readCurve(Keys.OUTPUT_CURVE, defaults.outputCurve),
            keyConfig = KeyMappingConfig(
                tapStep = preferences.readDouble(Keys.TAP_STEP)
                    ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                    ?: defaultKeyConfig.tapStep,
                holdDelayMillis = preferences.readLong(Keys.HOLD_DELAY)
                    ?.takeIf { it >= 0L }
                    ?: defaultKeyConfig.holdDelayMillis,
                holdUnitsPerSecond = preferences.readDouble(Keys.HOLD_SPEED)
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: defaultKeyConfig.holdUnitsPerSecond,
                holdRampDurationMillis = preferences.readLong(Keys.RAMP_DURATION)
                    ?.takeIf { it > 0L }
                    ?: defaultKeyConfig.holdRampDurationMillis,
                holdMaximumMultiplier = preferences.readDouble(Keys.RAMP_MULTIPLIER)
                    ?.takeIf { it.isFinite() && it >= 1.0 }
                    ?: defaultKeyConfig.holdMaximumMultiplier,
                holdRampCurve = preferences.readCurve(
                    Keys.HOLD_CURVE,
                    defaultKeyConfig.holdRampCurve,
                ),
            ),
            quantizationMode = preferences.readQuantizationMode(defaults.quantizationMode),
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
        preferences[Keys.OUTPUT_CURVE] = encodeCurve(settings.outputCurve)
        preferences[Keys.TAP_STEP] = settings.keyConfig.tapStep
        preferences[Keys.HOLD_DELAY] = settings.keyConfig.holdDelayMillis
        preferences[Keys.HOLD_SPEED] = settings.keyConfig.holdUnitsPerSecond
        preferences[Keys.RAMP_DURATION] = settings.keyConfig.holdRampDurationMillis
        preferences[Keys.RAMP_MULTIPLIER] = settings.keyConfig.holdMaximumMultiplier
        preferences[Keys.HOLD_CURVE] = encodeCurve(settings.keyConfig.holdRampCurve)
        preferences[Keys.QUANTIZATION_MODE_NAME] = settings.quantizationMode.name
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

    private fun Preferences.readQuantizationMode(
        default: VolumeQuantizationMode,
    ): VolumeQuantizationMode {
        val named = readString(Keys.QUANTIZATION_MODE_NAME)?.let { storedName ->
            VolumeQuantizationMode.entries.firstOrNull { it.name == storedName }
        }
        if (named != null) return named
        return readInt(Keys.LEGACY_QUANTIZATION_MODE_ORDINAL)
            ?.let(VolumeQuantizationMode.entries::getOrNull)
            ?: default
    }

    private fun Preferences.readCurve(
        key: Preferences.Key<String>,
        default: MappingCurve,
    ): MappingCurve = readString(key)
        ?.let { encoded -> runCatching { decodeCurve(encoded) }.getOrNull() }
        ?: default

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

    private object Keys {
        val OUTPUT_CURVE = stringPreferencesKey("output_curve")
        val TAP_STEP = doublePreferencesKey("tap_step")
        val HOLD_DELAY = longPreferencesKey("hold_delay")
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
}
