package dev.spcdts.volumemapper.runtime

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import dev.spcdts.volumemapper.VolumeMapperApplication
import java.io.FileDescriptor
import java.io.PrintWriter

class VolumeKeyAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var runtimeAnchor: AccessibilityRuntimeAnchor? = null
    private var runtimeAnchorRetryCount = 0
    private val runtimeAnchorRetry = Runnable(::attachRuntimeAnchor)
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            // 睡眠期间可能更换音频路由或丢失 UP；唤醒时清理旧手势并重新读取能力。
            runtimeAnchorRetryCount = 0
            attachRuntimeAnchor()
            coordinator.refreshSnapshot()
        }
    }

    private val coordinator: MappingCoordinator
        get() = (application as VolumeMapperApplication).graph.mappingCoordinator

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.keyDeliveryMonitor.reset()
        attachRuntimeAnchor()
        coordinator.setAccessibilityConnected(true)
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = coordinator.handleAccessibilityKey(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val delivery = coordinator.keyDeliveryMonitor.state.value
        writer.println("receivedDowns=${delivery.receivedDowns}")
        writer.println("expiredDowns=${delivery.expiredDowns}")
        writer.println("lastDelayMillis=${delivery.lastDelayMillis}")
        writer.println("anchorAttached=${delivery.anchorAttached}")
        writer.println("canInterceptKeys=${coordinator.runtime.value.canInterceptKeys}")
    }

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
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        mainHandler.removeCallbacks(runtimeAnchorRetry)
        runtimeAnchorRetryCount = 0
        runtimeAnchor?.detach()
        runtimeAnchor = null
        coordinator.keyDeliveryMonitor.reset()
    }

    private fun attachRuntimeAnchor() {
        mainHandler.removeCallbacks(runtimeAnchorRetry)
        val anchor = runtimeAnchor ?: AccessibilityRuntimeAnchor(this).also { runtimeAnchor = it }
        val attached = anchor.attach()
        coordinator.keyDeliveryMonitor.setAnchorAttached(attached)
        if (attached) {
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
