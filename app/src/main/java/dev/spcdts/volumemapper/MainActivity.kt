package dev.spcdts.volumemapper

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.spcdts.volumemapper.ui.VolumeMapperApp
import dev.spcdts.volumemapper.ui.VolumeMapperTheme

class MainActivity : ComponentActivity() {
    private val mappingCoordinator
        get() = (application as VolumeMapperApplication).graph.mappingCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            VolumeMapperTheme {
                VolumeMapperApp((application as VolumeMapperApplication).graph)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mappingCoordinator.onUiStarted()
    }

    override fun onResume() {
        super.onResume()
        mappingCoordinator.refreshSnapshot()
    }

    override fun onStop() {
        mappingCoordinator.onUiStopped()
        super.onStop()
    }
}
