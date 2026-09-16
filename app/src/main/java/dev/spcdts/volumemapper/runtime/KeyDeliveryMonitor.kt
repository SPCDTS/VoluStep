package dev.spcdts.volumemapper.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class KeyDeliveryState(
    val receivedDowns: Long = 0,
    val expiredDowns: Long = 0,
    val lastDelayMillis: Long? = null,
    val anchorAttached: Boolean = false,
)

/** 只计数音量键初始 DOWN，不保存按键内容，也不执行系统 I/O。断连后重新计数。 */
class KeyDeliveryMonitor {
    private val mutableState = MutableStateFlow(KeyDeliveryState())
    val state = mutableState.asStateFlow()

    fun recordDown(nowUptimeMillis: Long, eventTimeMillis: Long) {
        val expired = isAccessibilityKeyEventExpired(nowUptimeMillis, eventTimeMillis)
        val delay = if (eventTimeMillis > nowUptimeMillis) 0L else
            (nowUptimeMillis - eventTimeMillis).let { if (it < 0L) Long.MAX_VALUE else it }
        mutableState.update {
            it.copy(
                receivedDowns = it.receivedDowns + 1,
                expiredDowns = it.expiredDowns + if (expired) 1 else 0,
                lastDelayMillis = delay,
            )
        }
    }

    fun setAnchorAttached(attached: Boolean) {
        mutableState.update { it.copy(anchorAttached = attached) }
    }

    fun reset() { mutableState.value = KeyDeliveryState() }
}
