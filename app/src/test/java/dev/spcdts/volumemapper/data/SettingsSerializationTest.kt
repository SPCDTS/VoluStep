package dev.spcdts.volumemapper.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.StepVolumeMap
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test
    fun `current settings and v4 codec scenarios`() {
        `settings round trip preserves integer x step map and fixed hold interval`()
        `step map v4 codec round trips integer x and migrates v3`()
    }

    @Test
    fun `settings write actor scenarios`() {
        `settings write actor preserves fifo acknowledgements and survives one failure`()
    }

    @Test
    fun `version migration and fallback scenarios`() {
        `step map v2 migrates onto integer x and dense v3 preserves runtime shape`()
        `step map v1 migrates losslessly with every press state as a control point`()
        `legacy v2 curve and point zero zero seven tap step migrate to 143 presses`()
        `malformed new step payload falls back to legacy curve and tap step`()
        `bad fields fall back independently while valid fields survive`()
    }

    @Test
    fun `downgrade shadow restores every supported press count`() {
        val outputCurveKey = stringPreferencesKey("output_curve")
        val tapStepKey = doublePreferencesKey("tap_step")

        (1..150).forEach { pressCount ->
            val encoded = mutablePreferencesOf()
            SettingsSerialization.encode(
                preferences = encoded,
                settings = VolumeMapperSettings(
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

    fun `settings round trip preserves integer x step map and fixed hold interval`() {
        val outputMap = StepVolumeMap(
            basisSpan = 150,
            pressCount = 9,
            pressPositions = listOf(0, 1, 3, 6, 8, 9),
            offsets = listOf(0, 1, 4, 17, 58, 150),
        )
        val settings = VolumeMapperSettings(
            outputMap = outputMap,
            keyConfig = VolumeMapperSettings().keyConfig.copy(
                holdDelayMillis = 420L,
                holdStepIntervalMillis = 140L,
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
        assertEquals(140L, decoded.keyConfig.holdStepIntervalMillis)
        assertEquals(
            "v4|150|9|0,0;1,1;3,4;6,17;8,58;9,150",
            preferences[outputMapKey],
        )
        assertEquals(
            listOf(0, 2, 6, 13, 18),
            VolumeMapperSettings().outputMap.pressPositions,
        )
        assertEquals("INDEX", preferences[modeNameKey])
        assertNull(preferences[legacyModeKey])
    }

    fun `step map v4 codec round trips integer x and migrates v3`() {
        val map = StepVolumeMap(
            basisSpan = 12,
            pressCount = 6,
            pressPositions = listOf(0, 1, 4, 6),
            offsets = listOf(0, 1, 5, 12),
        )
        val encoded = SettingsSerialization.encodeStepVolumeMap(map)

        assertEquals("v4|12|6|0,0;1,1;4,5;6,12", encoded)
        assertEquals(map, SettingsSerialization.decodeStepVolumeMap(encoded))
        assertThrowsIllegalArgument {
            SettingsSerialization.decodeStepVolumeMap("v4|12|6|0,0;4,5;3,7;6,12")
        }
        assertThrowsIllegalArgument {
            SettingsSerialization.decodeStepVolumeMap("v4|12|6|0,0;4,5;6")
        }

        val migratedV3 = SettingsSerialization.decodeStepVolumeMap(
            "v3|18|18|0.0,0;0.12,1;0.34,5;0.70,16;1.0,18",
        )
        assertEquals(listOf(0, 2, 6, 13, 18), migratedV3.pressPositions)
        assertEquals(listOf(0, 1, 5, 16, 18), migratedV3.offsets)
    }

    fun `settings write actor preserves fifo acknowledgements and survives one failure`() =
        runBlocking {
            val base = VolumeMapperSettings()
            val writes = Channel<SettingsWriteRequest>(Channel.UNLIMITED)
            val persisted = mutableListOf<VolumeMapperSettings>()
            val writer = launch {
                collectSettingsWriteRequests(writes, debounceMillis = 10L) { request ->
                    persisted += request.settings
                }
            }
            val acknowledged = CompletableDeferred<Unit>()
            val flushed = base.copy(showSystemVolumeUi = false)
            val later = base.copy(disclosureAccepted = true)
            writes.send(SettingsWriteRequest(base, immediate = false))
            writes.send(SettingsWriteRequest(base.copy(keyConfig = base.keyConfig.copy(holdDelayMillis = 500L)), immediate = false))
            writes.send(SettingsWriteRequest(flushed, immediate = true, completion = acknowledged))
            writes.send(SettingsWriteRequest(later, immediate = false))

            withTimeout(1_000L) { acknowledged.await() }
            withTimeout(1_000L) {
                while (persisted.size < 2) yield()
            }
            assertEquals(listOf(flushed, later), persisted)
            writer.cancelAndJoin()

            val staleWrites = Channel<SettingsWriteRequest>(Channel.UNLIMITED)
            val stalePersisted = mutableListOf<VolumeMapperSettings>()
            val staleWriter = launch {
                collectSettingsWriteRequests(staleWrites, debounceMillis = 10_000L) { request ->
                    stalePersisted += request.settings
                }
            }
            val staleEnqueueTime = System.nanoTime() / 1_000_000L - 20_000L
            staleWrites.send(
                SettingsWriteRequest(
                    base,
                    immediate = false,
                    enqueuedAtMillis = staleEnqueueTime,
                ),
            )
            staleWrites.send(
                SettingsWriteRequest(
                    later,
                    immediate = false,
                    enqueuedAtMillis = staleEnqueueTime,
                ),
            )
            withTimeout(1_000L) {
                while (stalePersisted.isEmpty()) yield()
            }
            assertEquals(listOf(later), stalePersisted)
            staleWriter.cancelAndJoin()

            val retryWrites = Channel<SettingsWriteRequest>(Channel.UNLIMITED)
            var attempts = 0
            val retryWriter = launch {
                collectSettingsWriteRequests(retryWrites, debounceMillis = 10L) { request ->
                    attempts += 1
                    if (attempts == 1) throw IOException("transient")
                    persisted += request.settings
                }
            }
            val failedAck = CompletableDeferred<Unit>()
            val recoveredAck = CompletableDeferred<Unit>()
            retryWrites.send(SettingsWriteRequest(base, immediate = true, completion = failedAck))
            val failure = runCatching {
                withTimeout(1_000L) { failedAck.await() }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            retryWrites.send(SettingsWriteRequest(later, immediate = true, completion = recoveredAck))
            withTimeout(1_000L) { recoveredAck.await() }
            assertEquals(2, attempts)
            assertEquals(later, persisted.last())
            retryWriter.cancelAndJoin()
        }

    fun `step map v2 migrates onto integer x and dense v3 preserves runtime shape`() {
        val migrated = SettingsSerialization.decodeStepVolumeMap("v2|12|6|0,1,5,12")

        assertEquals(6, migrated.pressCount)
        assertEquals(listOf(0, 2, 4, 6), migrated.pressPositions)
        assertEquals(listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0), migrated.normalizedXs)
        assertEquals(listOf(0, 1, 5, 12), migrated.offsets)
        assertThrowsIllegalArgument {
            SettingsSerialization.decodeStepVolumeMap("v2|12|6|0,5,4,12")
        }

        // v1-v3 allowed P > K + 1. Migration samples the old curve at every representable
        // integer press instead of discarding it and falling back to the default map.
        val denseV3 = SettingsSerialization.decodeStepVolumeMap(
            "v3|12|3|0.0,0;0.1,1;0.2,2;0.4,4;0.6,7;0.8,9;1.0,12",
        )
        assertEquals(3, denseV3.pressCount)
        assertEquals(listOf(0, 1, 2, 3), denseV3.pressPositions)
        assertEquals(listOf(0, 3, 8, 12), denseV3.offsets)
        assertEquals(listOf(0, 3, 8, 12), denseV3.bind(RouteVolumeRange(0, 12)).indices)
    }

    fun `step map v1 migrates losslessly with every press state as a control point`() {
        val migrated = SettingsSerialization.decodeStepVolumeMap("v1|12|0,1,5,12")

        assertEquals(3, migrated.pressCount)
        assertEquals(4, migrated.controlPointCount)
        assertEquals(listOf(0, 1, 2, 3), migrated.pressPositions)
        assertEquals(listOf(0, 1, 5, 12), migrated.offsets)
        assertEquals(listOf(0, 1, 5, 12), migrated.bind(RouteVolumeRange(0, 12)).indices)
    }

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
            controlPointCount = 4,
        )

        assertEquals(143, decoded.outputMap.pressCount)
        assertEquals(expected, decoded.outputMap)
    }

    fun `malformed new step payload falls back to legacy curve and tap step`() {
        val encodedLegacyCurve = "v2|0.04|0.0,0.0;0.5,0.2;1.0,1.0"
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_step_map") to
                "v3|150|5|0.0,0;0.5,70;0.6,60;1.0,150",
            stringPreferencesKey("output_curve") to encodedLegacyCurve,
            doublePreferencesKey("tap_step") to 0.2,
        )

        val decoded = SettingsSerialization.decode(preferences)
        val expected = StepVolumeMap.fromCurve(
            curve = SettingsSerialization.decodeCurve(encodedLegacyCurve),
            basisSpan = 150,
            pressCount = 5,
            controlPointCount = 3,
        )

        assertEquals(expected, decoded.outputMap)
    }

    fun `bad fields fall back independently while valid fields survive`() {
        val defaults = VolumeMapperSettings()
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("output_step_map") to "v3|150|50|broken",
            stringPreferencesKey("output_curve") to "v2|NaN|0.0,0.0;1.0,1.0",
            stringPreferencesKey("tap_step") to "wrong preference type",
            longPreferencesKey("hold_delay") to -5L,
            longPreferencesKey("hold_step_interval") to 59L,
            // Valid obsolete ramp fields must not affect the fixed interval model.
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
        assertEquals(
            defaults.keyConfig.holdStepIntervalMillis,
            decoded.keyConfig.holdStepIntervalMillis,
        )
        assertFalse(decoded.showSystemVolumeUi)
        assertTrue(decoded.disclosureAccepted)
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
