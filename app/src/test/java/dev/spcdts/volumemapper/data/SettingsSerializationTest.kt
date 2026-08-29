package dev.spcdts.volumemapper.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.spcdts.volumemapper.core.FixedVolumePresets
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.StepVolumeMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test
    fun `current codec round trip preserves the authored integer map`() {
        val outputMap = StepVolumeMap(
            basisSpan = 150,
            pressCount = 9,
            pressPositions = listOf(0, 1, 3, 6, 8, 9),
            offsets = listOf(0, 1, 4, 17, 58, 150),
        )
        val settings = VolumeMapperSettings(
            outputMap = outputMap,
            fixedVolumePresets = FixedVolumePresets(listOf(20, 5, 12, 5, 0)),
            keyConfig = VolumeMapperSettings().keyConfig.copy(
                holdStepIntervalMillis = 140L,
            ),
            showSystemVolumeUi = false,
            disclosureAccepted = true,
        )
        val preferences = mutablePreferencesOf(
            longPreferencesKey("hold_delay") to 800L,
        )

        SettingsSerialization.encode(preferences, settings)

        assertEquals(settings, SettingsSerialization.decode(preferences))
        assertEquals(
            "v4|150|9|0,0;1,1;3,4;6,17;8,58;9,150",
            preferences[stringPreferencesKey("output_step_map")],
        )
        assertEquals(
            "0,5,12,20",
            preferences[stringPreferencesKey("fixed_volume_presets")],
        )
        assertEquals(null, preferences[longPreferencesKey("hold_delay")])
    }

    @Test
    fun `settings writer coalesces debounced tail behind an immediate acknowledgement`() = runBlocking {
        val base = VolumeMapperSettings()
        val stale = base.copy(keyConfig = base.keyConfig.copy(holdStepIntervalMillis = 140L))
        val flushed = base.copy(showSystemVolumeUi = false)
        val tail = base.copy(disclosureAccepted = true)
        val writes = Channel<SettingsWriteRequest>(Channel.UNLIMITED)
        val persisted = mutableListOf<VolumeMapperSettings>()
        val writer = launch {
            collectSettingsWriteRequests(writes, debounceMillis = 10L) { request ->
                persisted += request.settings
            }
        }
        val acknowledged = CompletableDeferred<Unit>()

        writes.send(SettingsWriteRequest(base, immediate = false))
        writes.send(SettingsWriteRequest(stale, immediate = false))
        writes.send(SettingsWriteRequest(flushed, immediate = true, completion = acknowledged))
        writes.send(SettingsWriteRequest(tail, immediate = false))
        withTimeout(1_000L) { acknowledged.await() }
        withTimeout(1_000L) {
            while (persisted.size < 2) yield()
        }

        assertEquals(listOf(flushed, tail), persisted)
        writer.cancelAndJoin()
    }

    @Test
    fun `legacy payloads malformed fields and downgrade shadows remain compatible`() {
        val v1 = SettingsSerialization.decodeStepVolumeMap("v1|12|0,1,5,12")
        val v2 = SettingsSerialization.decodeStepVolumeMap("v2|12|6|0,1,5,12")
        val v3 = SettingsSerialization.decodeStepVolumeMap(
            "v3|12|3|0.0,0;0.1,1;0.2,2;0.4,4;0.6,7;0.8,9;1.0,12",
        )

        assertEquals(listOf(0, 1, 2, 3), v1.pressPositions)
        assertEquals(listOf(0, 1, 5, 12), v1.bind(RouteVolumeRange(0, 12)).indices)
        assertEquals(listOf(0, 2, 4, 6), v2.pressPositions)
        assertEquals(listOf(0, 1, 5, 12), v2.offsets)
        assertEquals(listOf(0, 1, 2, 3), v3.pressPositions)
        assertEquals(listOf(0, 3, 8, 12), v3.bind(RouteVolumeRange(0, 12)).indices)

        val encodedLegacyCurve = "v2|0.04|0.0,0.0;0.5,0.2;1.0,1.0"
        val legacyFallback = SettingsSerialization.decode(
            mutablePreferencesOf(
                stringPreferencesKey("output_step_map") to
                    "v3|150|5|0.0,0;0.5,70;0.6,60;1.0,150",
                stringPreferencesKey("output_curve") to encodedLegacyCurve,
                doublePreferencesKey("tap_step") to 0.2,
            ),
        )
        assertEquals(
            StepVolumeMap.fromCurve(
                curve = SettingsSerialization.decodeCurve(encodedLegacyCurve),
                basisSpan = 150,
                pressCount = 5,
                controlPointCount = 3,
            ),
            legacyFallback.outputMap,
        )
        assertEquals(FixedVolumePresets(), legacyFallback.fixedVolumePresets)

        assertEquals(
            listOf(0, 5, 12, 20),
            SettingsSerialization.decodeFixedVolumePresets("20,5,12,5,0").indices,
        )

        val defaults = VolumeMapperSettings()
        val malformed = SettingsSerialization.decode(
            mutablePreferencesOf(
                stringPreferencesKey("output_step_map") to "v3|150|50|broken",
                stringPreferencesKey("output_curve") to "v2|NaN|0.0,0.0;1.0,1.0",
                stringPreferencesKey("tap_step") to "wrong preference type",
                longPreferencesKey("hold_delay") to 800L,
                longPreferencesKey("hold_step_interval") to 59L,
                doublePreferencesKey("hold_speed") to 0.27,
                stringPreferencesKey("fixed_volume_presets") to "0,-1,12",
                booleanPreferencesKey("show_system_ui") to false,
                booleanPreferencesKey("disclosure_accepted") to true,
            ),
        )
        assertEquals(defaults.outputMap, malformed.outputMap)
        assertEquals(defaults.fixedVolumePresets, malformed.fixedVolumePresets)
        assertEquals(defaults.keyConfig, malformed.keyConfig)
        assertFalse(malformed.showSystemVolumeUi)
        assertTrue(malformed.disclosureAccepted)

        val brokenPresets = SettingsSerialization.decode(
            mutablePreferencesOf(
                stringPreferencesKey("fixed_volume_presets") to "0,not-an-index,12",
            ),
        )
        assertEquals(defaults.fixedVolumePresets, brokenPresets.fixedVolumePresets)

        val outputCurveKey = stringPreferencesKey("output_curve")
        val tapStepKey = doublePreferencesKey("tap_step")
        (1..150).forEach { pressCount ->
            val encoded = mutablePreferencesOf()
            SettingsSerialization.encode(
                encoded,
                VolumeMapperSettings(
                    outputMap = StepVolumeMap.linear(
                        basisSpan = 150,
                        pressCount = pressCount,
                        controlPointCount = 2,
                    ),
                ),
            )
            val legacyOnly = mutablePreferencesOf(
                outputCurveKey to requireNotNull(encoded[outputCurveKey]),
                tapStepKey to requireNotNull(encoded[tapStepKey]),
            )
            assertEquals(
                "pressCount=$pressCount",
                pressCount,
                SettingsSerialization.decode(legacyOnly).outputMap.pressCount,
            )
        }
    }

}
