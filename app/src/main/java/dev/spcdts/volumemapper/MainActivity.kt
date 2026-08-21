package dev.spcdts.volumemapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.spcdts.volumemapper.ui.VolumeMapperApp
import dev.spcdts.volumemapper.ui.VolumeMapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            VolumeMapperTheme {
                VolumeMapperApp((application as VolumeMapperApplication).graph)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as VolumeMapperApplication).graph.mappingCoordinator.refreshSnapshot()
    }
}
