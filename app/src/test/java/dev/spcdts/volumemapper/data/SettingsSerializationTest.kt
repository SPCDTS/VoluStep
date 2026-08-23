package dev.spcdts.volumemapper.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.spcdts.volumemapper.core.MappingCurve
import dev.spcdts.volumemapper.core.MappingPoint
import dev.spcdts.volumemapper.core.StepVolumeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test
    fun `settings round trip preserves step map and hold curve`() {
        val outputMap = StepVolumeMap(
            basisSpan = 150,
            offsets = listOf(0, 1, 4, 17, 58, 150),
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
            outputMap = outputMap,
            keyConfig = VolumeMapperSettings().keyConfig.copy(
                holdDelayMillis = 420L,
                holdUnitsPerSecond = 0.22,
                holdRampDurationMillis = 2_200L,
                holdMaximumMultiplier = 5.5,
                holdRampCurve = holdCurve,
            ),
            showSystemVolumeUi = false,
            disclosureAccepted = true,
        )
        val legacyModeKey = intPreferencesKey("quantization_mode")
        val modeNameKey = stringPreferencesKey("quantization_mode_name")
        val outputMapKey = stringPreferencesKey("output_step_map")
        val preferences = mutablePreferencesOf(legacyModeKey to 1)

        SettingsSerialization.encode(preferences, settings)
        val decoded = SettingsSerialization.decode(preferences)

        assertEquals(settings, decoded)
        assertEquals(outputMap, decoded.outputMap)
        assertEquals(0.1, decoded.keyConfig.holdRampCurve.minimumXSpacing, TOLERANCE)
        assertEquals("v1|150|0,1,4,17,58,150", preferences[outputMapKey])
        assertEquals("INDEX", preferences[modeNameKey])
        assertNull(preferences[legacyModeKey])
    }

    @Test
    fun `step map v1 codec round trips and rejects malformed payload`() {
        val map = StepVolumeMap(
            basisSpan = 12,
            offsets = listOf(0, 1, 5, 12),
        )
        val encoded = SettingsSerialization.encodeStepVolumeMap(map)

        assertEquals("v1|12|0,1,5,12", encoded)
        assertEquals(map, SettingsSerialization.decodeStepVolumeMap(encoded))
        assertThrowsIllegalArgument {
            SettingsSerialization.decodeStepVolumeMap("v1|12|0,5,4,12")
        }
    }

    @Test
    fun `legacy v2 curve and point zero zero seven tap step migrate to 143 presses`() {
        val encodedCurve = "v2|0.02|0.0,0.0;0.3,0.08;0.7,0.45;1.0,1.0"
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_curve") to encodedCurve,
            doublePreferencesKey("tap_step") to 0.007,
        )

        val decoded = SettingsSerialization.decode(preferences)
        val expected = StepVolumeMap.fromCurve(
            curve = SettingsSerialization.decodeCurve(encodedCurve),
            basisSpan = 150,
            pressCount = 143,
        )

        assertEquals(143, decoded.outputMap.pressCount)
        assertEquals(expected, decoded.outputMap)
    }

    @Test
    fun `malformed new step payload falls back to legacy curve and tap step`() {
        val encodedLegacyCurve = "v2|0.04|0.0,0.0;0.5,0.2;1.0,1.0"
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_step_map") to "v1|150|0,70,60,150",
            stringPreferencesKey("output_curve") to encodedLegacyCurve,
            doublePreferencesKey("tap_step") to 0.2,
        )

        val decoded = SettingsSerialization.decode(preferences)
        val expected = StepVolumeMap.fromCurve(
            curve = SettingsSerialization.decodeCurve(encodedLegacyCurve),
            basisSpan = 150,
            pressCount = 5,
        )

        assertEquals(expected, decoded.outputMap)
    }

    @Test
    fun `bad fields fall back independently while valid fields survive`() {
        val defaults = VolumeMapperSettings()
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_step_map") to "v1|150|broken",
            stringPreferencesKey("output_curve") to "v2|NaN|0.0,0.0;1.0,1.0",
            stringPreferencesKey("tap_step") to "wrong preference type",
            longPreferencesKey("hold_delay") to -5L,
            doublePreferencesKey("hold_speed") to 0.27,
            longPreferencesKey("ramp_duration") to 0L,
            doublePreferencesKey("ramp_multiplier") to 0.5,
            stringPreferencesKey("hold_curve") to "not-a-curve",
            booleanPreferencesKey("show_system_ui") to false,
            booleanPreferencesKey("disclosure_accepted") to true,
        )

        val decoded = SettingsSerialization.decode(preferences)

        assertEquals(defaults.outputMap, decoded.outputMap)
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
        assertFalse(decoded.showSystemVolumeUi)
        assertTrue(decoded.disclosureAccepted)
    }

    @Test
    fun `encode keeps v2 curve and tap step shadow for downgraded builds`() {
        val outputMap = StepVolumeMap(
            basisSpan = 12,
            offsets = listOf(0, 1, 5, 12),
        )
        val preferences = mutablePreferencesOf()

        SettingsSerialization.encode(
            preferences = preferences,
            settings = VolumeMapperSettings(outputMap = outputMap),
        )

        val encodedShadow = requireNotNull(preferences[stringPreferencesKey("output_curve")])
        val decodedShadow = SettingsSerialization.decodeCurve(encodedShadow)
        assertTrue(encodedShadow.startsWith("v2|"))
        assertEquals(1.0 / 3.0, decodedShadow.minimumXSpacing, TOLERANCE)
        assertEquals(listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0), decodedShadow.points.map { it.x })
        assertEquals(listOf(0.0, 1.0 / 12.0, 5.0 / 12.0, 1.0), decodedShadow.points.map { it.y })
        assertEquals(
            1.0 / 3.0,
            preferences[doublePreferencesKey("tap_step")] ?: error("Missing tap step shadow"),
            TOLERANCE,
        )
    }

    @Test
    fun `maximum one hundred fifty press map keeps a valid downgrade shadow`() {
        val outputMap = StepVolumeMap.linear(basisSpan = 150, pressCount = 150)
        val preferences = mutablePreferencesOf()

        SettingsSerialization.encode(
            preferences = preferences,
            settings = VolumeMapperSettings(outputMap = outputMap),
        )

        assertEquals(
            outputMap,
            SettingsSerialization.decode(preferences).outputMap,
        )
        val shadow = SettingsSerialization.decodeCurve(
            requireNotNull(preferences[stringPreferencesKey("output_curve")]),
        )
        assertEquals(151, shadow.points.size)
        assertEquals(1.0 / 150.0, shadow.minimumXSpacing, TOLERANCE)
    }

    @Test
    fun `curve codec rejects malformed v2 without affecting legacy support`() {
        val curve = MappingCurve.linear()
        val encoded = SettingsSerialization.encodeCurve(curve)

        assertEquals(curve, SettingsSerialization.decodeCurve(encoded))
        assertThrowsIllegalArgument {
            SettingsSerialization.decodeCurve("v2|0.02|broken")
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
