package dev.spcdts.volumemapper.runtime

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 在无障碍连接期间维持一个不可交互的 1 px 运行锚点。
 *
 * 该窗口只用于验证部分 OEM 是否会对“仍持有无障碍窗口”的服务采用不同后台策略；
 * 它不授予权限、不替代前台服务，也不保证息屏按键会被系统继续分发。
 */
internal class AccessibilityRuntimeAnchor(
    private val service: AccessibilityService,
) {
    private var windowManager: WindowManager? = null
    private var anchorView: View? = null

    fun attach(): Boolean {
        if (anchorView?.isAttachedToWindow == true) return true
        if (anchorView != null) detach()

        val manager = service.getSystemService(WindowManager::class.java)
        val view = View(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val layoutParams = WindowManager.LayoutParams(
            WINDOW_SIZE_PX,
            WINDOW_SIZE_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.START or Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            title = windowTitle(service.packageName)
        }

        try {
            manager.addView(view, layoutParams)
            windowManager = manager
            anchorView = view
            return view.isAttachedToWindow
        } catch (error: RuntimeException) {
            if (!error.isExpectedWindowLifecycleFailure()) throw error
            Log.w(TAG, "Unable to attach accessibility runtime anchor", error)
            return false
        }
    }

    fun detach() {
        val view = anchorView ?: return
        val manager = windowManager
        anchorView = null
        windowManager = null
        if (manager == null) return

        try {
            manager.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            if (!error.isExpectedWindowLifecycleFailure()) throw error
            Log.w(TAG, "Unable to detach accessibility runtime anchor", error)
        }
    }

    private fun RuntimeException.isExpectedWindowLifecycleFailure(): Boolean =
        this is WindowManager.BadTokenException ||
            this is WindowManager.InvalidDisplayException ||
            this is SecurityException ||
            this is IllegalArgumentException ||
            this is IllegalStateException

    internal companion object {
        private const val WINDOW_TITLE_SUFFIX = "VoluStepRuntimeAnchor"
        private const val WINDOW_SIZE_PX = 1
        private const val TAG = "VoluStepAnchor"

        fun windowTitle(packageName: String): String = "$packageName:$WINDOW_TITLE_SUFFIX"
    }
}
