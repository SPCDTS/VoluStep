package dev.spcdts.volumemapper.ui

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDetailsSettingsLauncherTest {
    @Test
    fun `application details is preferred before generic settings`() {
        val candidates = AppDetailsSettingsLauncher.intentCandidates("dev.example.volume")

        assertEquals(
            SettingsIntentSpec(
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                dataUri = "package:dev.example.volume",
            ),
            candidates.first(),
        )
        assertEquals(Settings.ACTION_SETTINGS, candidates.last().action)
        assertEquals(null, candidates.last().dataUri)
    }

    @Test
    fun `first available candidate is launched`() {
        val launched = mutableListOf<String>()

        val result = AppDetailsSettingsLauncher.launchFirstAvailable(
            candidates = listOf("details", "settings"),
            launch = {
                launched += it
                true
            },
        )

        assertTrue(result)
        assertEquals(listOf("details"), launched)
    }

    @Test
    fun `unavailable details falls back to generic settings`() {
        val launched = mutableListOf<String>()

        val result = AppDetailsSettingsLauncher.launchFirstAvailable(
            candidates = listOf("details", "settings"),
            launch = {
                launched += it
                it == "settings"
            },
        )

        assertTrue(result)
        assertEquals(listOf("details", "settings"), launched)
    }

    @Test
    fun `no available candidate reports failure`() {
        val result = AppDetailsSettingsLauncher.launchFirstAvailable(
            candidates = listOf("details", "settings"),
            launch = { false },
        )

        assertFalse(result)
    }
}
