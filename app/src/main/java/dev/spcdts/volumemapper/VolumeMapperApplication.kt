package dev.spcdts.volumemapper

import android.app.Application
import dev.spcdts.volumemapper.audio.AudioManagerVolumeBackend
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.runtime.MappingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VolumeMapperApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(application: Application) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(application, applicationScope)
    val volumeBackend = AudioManagerVolumeBackend(application)
    val mappingCoordinator = MappingCoordinator(
        backend = volumeBackend,
        settingsRepository = settingsRepository,
        parentScope = applicationScope,
    )
}
