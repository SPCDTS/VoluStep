package dev.spcdts.volumemapper.runtime

/** AOSP Accessibility KeyEventDispatcher 等待服务回调结果的固定超时。 */
internal const val AOSP_ACCESSIBILITY_KEY_EVENT_TIMEOUT_MILLIS = 500L

internal enum class ExpiredKeyEventDisposition {
    PROCESS,
    PASS_THROUGH,
    DRAIN_OWNED_GESTURE,
}

/**
 * 用与 [android.view.KeyEvent.getEventTime] 相同的 uptime 时钟判断事件是否已错过系统分派窗口。
 *
 * OEM 偶尔会给出略晚于当前 uptime 的事件；这种负 age 按新事件处理。减法溢出则只可能
 * 表示跨越了极大的正向时间区间，保守地视作过期。
 */
internal fun isAccessibilityKeyEventExpired(
    nowUptimeMillis: Long,
    eventTimeMillis: Long,
    timeoutMillis: Long = AOSP_ACCESSIBILITY_KEY_EVENT_TIMEOUT_MILLIS,
): Boolean {
    require(timeoutMillis >= 0L)
    if (nowUptimeMillis < eventTimeMillis) return false

    val ageMillis = nowUptimeMillis - eventTimeMillis
    return ageMillis < 0L || ageMillis >= timeoutMillis
}

/** 纯决策层：过期初始 DOWN 永不接管，已有 owner 的过期尾事件只用于排空手势。 */
internal fun expiredKeyEventDisposition(
    isExpired: Boolean,
    isInitialDown: Boolean,
    ownsGesture: Boolean,
): ExpiredKeyEventDisposition = when {
    !isExpired -> ExpiredKeyEventDisposition.PROCESS
    isInitialDown -> ExpiredKeyEventDisposition.PASS_THROUGH
    ownsGesture -> ExpiredKeyEventDisposition.DRAIN_OWNED_GESTURE
    else -> ExpiredKeyEventDisposition.PASS_THROUGH
}
