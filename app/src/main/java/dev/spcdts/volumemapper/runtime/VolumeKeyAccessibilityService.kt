package dev.spcdts.volumemapper.runtime

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.spcdts.volumemapper.VolumeMapperApplication

class VolumeKeyAccessibilityService : AccessibilityService() {
    private val coordinator: MappingCoordinator
        get() = (application as VolumeMapperApplication).graph.mappingCoordinator

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.setAccessibilityConnected(true)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = coordinator.handleAccessibilityKey(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        coordinator.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        coordinator.setAccessibilityConnected(false)
        super.onDestroy()
    }
}
