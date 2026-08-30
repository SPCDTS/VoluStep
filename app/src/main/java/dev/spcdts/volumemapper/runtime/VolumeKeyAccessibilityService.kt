package dev.spcdts.volumemapper.runtime

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.spcdts.volumemapper.VolumeMapperApplication

class VolumeKeyAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var runtimeAnchor: AccessibilityRuntimeAnchor? = null
    private var runtimeAnchorRetryCount = 0
    private val runtimeAnchorRetry = Runnable(::attachRuntimeAnchor)

    private val coordinator: MappingCoordinator
        get() = (application as VolumeMapperApplication).graph.mappingCoordinator

    override fun onServiceConnected() {
        super.onServiceConnected()
        attachRuntimeAnchor()
        coordinator.setAccessibilityConnected(true)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = coordinator.handleAccessibilityKey(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        detachRuntimeAnchor()
        coordinator.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachRuntimeAnchor()
        coordinator.setAccessibilityConnected(false)
        super.onDestroy()
    }

    private fun detachRuntimeAnchor() {
        mainHandler.removeCallbacks(runtimeAnchorRetry)
        runtimeAnchorRetryCount = 0
        runtimeAnchor?.detach()
        runtimeAnchor = null
    }

    private fun attachRuntimeAnchor() {
        val anchor = runtimeAnchor ?: AccessibilityRuntimeAnchor(this).also { runtimeAnchor = it }
        if (anchor.attach()) {
            runtimeAnchorRetryCount = 0
            return
        }
        if (runtimeAnchorRetryCount >= MAX_RUNTIME_ANCHOR_RETRIES) return

        runtimeAnchorRetryCount += 1
        mainHandler.postDelayed(
            runtimeAnchorRetry,
            RUNTIME_ANCHOR_RETRY_DELAY_MILLIS * runtimeAnchorRetryCount,
        )
    }

    private companion object {
        const val MAX_RUNTIME_ANCHOR_RETRIES = 3
        const val RUNTIME_ANCHOR_RETRY_DELAY_MILLIS = 500L
    }
}
