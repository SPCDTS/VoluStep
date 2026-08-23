package dev.spcdts.volumemapper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

internal data class SettingsIntentSpec(
    val action: String,
    val dataUri: String? = null,
)

internal object AppDetailsSettingsLauncher {
    internal fun intentCandidates(packageName: String): List<SettingsIntentSpec> = listOf(
        SettingsIntentSpec(
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            dataUri = "package:$packageName",
        ),
        SettingsIntentSpec(action = Settings.ACTION_SETTINGS),
    )

    fun open(context: Context): Boolean {
        val intents = intentCandidates(context.packageName).map(::createIntent)
        return launchFirstAvailable(
            candidates = intents,
            launch = { intent ->
                try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            },
        )
    }

    internal fun createIntent(spec: SettingsIntentSpec): Intent = Intent(spec.action).apply {
        spec.dataUri?.let { data = it.toUri() }
    }

    internal fun <T> launchFirstAvailable(
        candidates: List<T>,
        launch: (T) -> Boolean,
    ): Boolean {
        for (candidate in candidates) {
            if (launch(candidate)) return true
        }
        return false
    }
}
