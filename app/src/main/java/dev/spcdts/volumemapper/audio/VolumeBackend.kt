package dev.spcdts.volumemapper.audio

import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import kotlinx.coroutines.flow.Flow

/** 系统音量边界；缓存属性不得在按键回调中执行 Binder I/O。 */
interface VolumeBackend {
    val isVolumeFixed: Boolean
    val isMediaContextSafe: Boolean
    fun refreshMediaContextSafety(): Boolean
    fun snapshot(): Result<RouteVolumeSnapshot>
    fun setMediaVolume(index: Int, showSystemUi: Boolean): Result<Unit>
    fun environmentChanges(): Flow<Unit>
}
