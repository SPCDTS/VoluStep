package dev.spcdts.volumemapper.ui

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDetailsSettingsLauncherTest {
    @Test
    fun `settings candidates and fallback scenarios`() {
        `application details is preferred before generic settings`()
        `first available candidate is launched`()
        `unavailable details falls back to generic settings`()
        `no available candidate reports failure`()
    }

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

    fun `no available candidate reports failure`() {
        val result = AppDetailsSettingsLauncher.launchFirstAvailable(
            candidates = listOf("details", "settings"),
            launch = { false },
        )

        assertFalse(result)
    }
}
