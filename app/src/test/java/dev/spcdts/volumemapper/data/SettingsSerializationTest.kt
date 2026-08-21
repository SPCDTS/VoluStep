package dev.spcdts.volumemapper.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.spcdts.volumemapper.core.MappingCurve
import dev.spcdts.volumemapper.core.MappingPoint
import dev.spcdts.volumemapper.core.VolumeQuantizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test
    fun `settings round trip preserves curves spacing and enum name`() {
        val outputCurve = MappingCurve(
            points = listOf(
                MappingPoint(0.0, 0.05),
                MappingPoint(0.4, 0.18),
                MappingPoint(1.0, 0.72),
            ),
            minimumXSpacing = 0.075,
        )
        val holdCurve = MappingCurve(
            points = listOf(
                MappingPoint(0.0, 0.0),
                MappingPoint(0.5, 0.3),
                MappingPoint(1.0, 1.0),
            ),
            minimumXSpacing = 0.1,
        )
        val settings = VolumeMapperSettings(
            outputCurve = outputCurve,
            keyConfig = VolumeMapperSettings().keyConfig.copy(
                tapStep = 0.031,
                holdDelayMillis = 420L,
                holdUnitsPerSecond = 0.22,
                holdRampDurationMillis = 2_200L,
                holdMaximumMultiplier = 5.5,
                holdRampCurve = holdCurve,
            ),
            quantizationMode = VolumeQuantizationMode.INDEX,
            showSystemVolumeUi = false,
            disclosureAccepted = true,
        )
        val legacyModeKey = intPreferencesKey("quantization_mode")
        val modeNameKey = stringPreferencesKey("quantization_mode_name")
        val preferences = mutablePreferencesOf(
            legacyModeKey to VolumeQuantizationMode.DECIBELS.ordinal,
        )

        SettingsSerialization.encode(preferences, settings)
        val decoded = SettingsSerialization.decode(preferences)

        assertEquals(settings, decoded)
        assertEquals(0.075, decoded.outputCurve.minimumXSpacing, TOLERANCE)
        assertEquals(0.1, decoded.keyConfig.holdRampCurve.minimumXSpacing, TOLERANCE)
        assertEquals(VolumeQuantizationMode.INDEX.name, preferences[modeNameKey])
        assertNull(preferences[legacyModeKey])
    }

    @Test
    fun `legacy point string and ordinal remain readable`() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_curve") to
                "0.0,0.0;0.25,0.04;0.5,0.16;0.75,0.45;1.0,1.0",
            intPreferencesKey("quantization_mode") to VolumeQuantizationMode.INDEX.ordinal,
        )

        val decoded = SettingsSerialization.decode(preferences)

        assertEquals(
            MappingCurve.DEFAULT_MINIMUM_X_SPACING,
            decoded.outputCurve.minimumXSpacing,
            TOLERANCE,
        )
        assertEquals(0.16, decoded.outputCurve.evaluate(0.5), TOLERANCE)
        assertEquals(VolumeQuantizationMode.INDEX, decoded.quantizationMode)
    }

    @Test
    fun `bad fields fall back independently while valid fields survive`() {
        val defaults = VolumeMapperSettings()
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_curve") to "v2|NaN|0.0,0.0;1.0,1.0",
            stringPreferencesKey("tap_step") to "wrong preference type",
            longPreferencesKey("hold_delay") to -5L,
            doublePreferencesKey("hold_speed") to 0.27,
            longPreferencesKey("ramp_duration") to 0L,
            doublePreferencesKey("ramp_multiplier") to 0.5,
            stringPreferencesKey("hold_curve") to "not-a-curve",
            stringPreferencesKey("quantization_mode_name") to "FUTURE_MODE",
            intPreferencesKey("quantization_mode") to VolumeQuantizationMode.INDEX.ordinal,
            booleanPreferencesKey("show_system_ui") to false,
            booleanPreferencesKey("disclosure_accepted") to true,
        )

        val decoded = SettingsSerialization.decode(preferences)

        assertEquals(defaults.outputCurve, decoded.outputCurve)
        assertEquals(defaults.keyConfig.tapStep, decoded.keyConfig.tapStep, TOLERANCE)
        assertEquals(defaults.keyConfig.holdDelayMillis, decoded.keyConfig.holdDelayMillis)
        assertEquals(0.27, decoded.keyConfig.holdUnitsPerSecond, TOLERANCE)
        assertEquals(
            defaults.keyConfig.holdRampDurationMillis,
            decoded.keyConfig.holdRampDurationMillis,
        )
        assertEquals(
            defaults.keyConfig.holdMaximumMultiplier,
            decoded.keyConfig.holdMaximumMultiplier,
            TOLERANCE,
        )
        assertEquals(defaults.keyConfig.holdRampCurve, decoded.keyConfig.holdRampCurve)
        assertEquals(VolumeQuantizationMode.INDEX, decoded.quantizationMode)
        assertFalse(decoded.showSystemVolumeUi)
        assertTrue(decoded.disclosureAccepted)
    }

    @Test
    fun `curve codec rejects malformed v2 without affecting legacy support`() {
        val curve = MappingCurve.linear()
        val encoded = SettingsSerialization.encodeCurve(curve)

        assertEquals(curve, SettingsSerialization.decodeCurve(encoded))
        try {
            SettingsSerialization.decodeCurve("v2|0.02|broken")
            throw AssertionError("Malformed curve should have failed")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
