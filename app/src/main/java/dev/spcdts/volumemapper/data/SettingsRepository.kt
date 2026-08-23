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
import dev.spcdts.volumemapper.core.StepVolumeMap
import java.io.IOException
import kotlin.math.ceil
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
    val outputMap: StepVolumeMap = StepVolumeMap.fromCurve(
        curve = MappingPreset.LOW_VOLUME_FINE.createCurve(),
        basisSpan = DEFAULT_OUTPUT_BASIS_SPAN,
        pressCount = DEFAULT_OUTPUT_PRESS_COUNT,
    ),
    val keyConfig: KeyMappingConfig = KeyMappingConfig(
        holdDelayMillis = 350L,
        holdUnitsPerSecond = 0.10,
        holdRampDurationMillis = 1_500L,
        holdMaximumMultiplier = 4.0,
        holdRampCurve = MappingCurve.linear(),
    ),
    val showSystemVolumeUi: Boolean = true,
    val disclosureAccepted: Boolean = false,
)

private const val DEFAULT_OUTPUT_BASIS_SPAN = 150
private const val DEFAULT_OUTPUT_PRESS_COUNT = 50

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

    fun updateOutputMap(outputMap: StepVolumeMap) = update { copy(outputMap = outputMap) }

    fun updateKeyConfig(config: KeyMappingConfig) = update { copy(keyConfig = config) }

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
            outputMap = preferences.readStepVolumeMap()
                ?: migrateLegacyOutputMap(preferences, defaults.outputMap),
            keyConfig = KeyMappingConfig(
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
        preferences[Keys.TAP_STEP] = 1.0 / settings.outputMap.pressCount.toDouble()
        preferences[Keys.HOLD_DELAY] = settings.keyConfig.holdDelayMillis
        preferences[Keys.HOLD_SPEED] = settings.keyConfig.holdUnitsPerSecond
        preferences[Keys.RAMP_DURATION] = settings.keyConfig.holdRampDurationMillis
        preferences[Keys.RAMP_MULTIPLIER] = settings.keyConfig.holdMaximumMultiplier
        preferences[Keys.HOLD_CURVE] = encodeCurve(settings.keyConfig.holdRampCurve)
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
        append(STEP_MAP_FORMAT_V1)
        append('|')
        append(map.basisSpan)
        append('|')
        append(map.offsets.joinToString(separator = ","))
    }

    fun decodeStepVolumeMap(encoded: String): StepVolumeMap {
        val components = encoded.split('|', limit = 3)
        require(components.size == 3 && components[0] == STEP_MAP_FORMAT_V1)
        val basisSpan = components[1].toInt()
        val offsets = components[2].split(',').map(String::toInt)
        return StepVolumeMap(basisSpan = basisSpan, offsets = offsets)
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
            ?.coerceIn(1, DEFAULT_OUTPUT_BASIS_SPAN)
            ?: default.pressCount
        return StepVolumeMap.fromCurve(
            curve = legacyCurve,
            basisSpan = DEFAULT_OUTPUT_BASIS_SPAN,
            pressCount = pressCount,
        )
    }

    private fun StepVolumeMap.toLegacyCurve(): MappingCurve = MappingCurve(
        points = offsets.mapIndexed { index, offset ->
            MappingPoint(
                x = index.toDouble() / pressCount.toDouble(),
                y = offset.toDouble() / basisSpan.toDouble(),
            )
        },
        minimumXSpacing = 1.0 / pressCount.toDouble(),
    )

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
        val OUTPUT_STEP_MAP = stringPreferencesKey("output_step_map")
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
    private const val STEP_MAP_FORMAT_V1 = "v1"
}
