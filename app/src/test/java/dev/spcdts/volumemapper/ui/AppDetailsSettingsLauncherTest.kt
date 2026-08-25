package dev.spcdts.volumemapper.ui

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDetailsSettingsLauncherTest {
    @Test
    fun `settings launcher falls back once and reports total failure`() {
        val launched = mutableListOf<SettingsIntentSpec>()
        val candidates = AppDetailsSettingsLauncher.intentCandidates("dev.example.volume")

        val result = AppDetailsSettingsLauncher.launchFirstAvailable(candidates) { candidate ->
            launched += candidate
            candidate.action == Settings.ACTION_SETTINGS
        }

        assertTrue(result)
        assertEquals(
            listOf(
                SettingsIntentSpec(
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    dataUri = "package:dev.example.volume",
                ),
                SettingsIntentSpec(action = Settings.ACTION_SETTINGS, dataUri = null),
            ),
            launched,
        )

        assertFalse(
            AppDetailsSettingsLauncher.launchFirstAvailable(candidates) { false },
        )
    }
}
