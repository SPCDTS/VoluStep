package dev.spcdts.volumemapper

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.spcdts.volumemapper.core.MappingCurve
import dev.spcdts.volumemapper.core.VolumeQuantizationMode
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.MappingControllerService
import dev.spcdts.volumemapper.runtime.MappingCoordinator
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器专用的真实系统链路 E2E。
 *
 * 与 [VolumeKeyAudioIntegrationTest] 不同，本测试会真正绑定 AccessibilityService、启动
 * specialUse FGS，并从 SystemUI 常驻通知执行“停止映射”。测试会修改 secure accessibility
 * settings，因此通过 emulator guard 禁止在真机执行。
 *
 * Instrumentation 自身也通过 UiAutomation 占用无障碍通道，不能可靠证明系统按键被目标
 * AccessibilityService 过滤。完整按键阶段由 scripts/emulator-e2e-test.ps1 在 runner 退出后执行。
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RealSystemVolumeE2eTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun disclosureAccessibilityControllerAndNotificationStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prepareExternalJourney =
            InstrumentationRegistry.getArguments().getString(ARG_PREPARE_EXTERNAL) == "true"
        assumeTrue("本测试会修改 secure settings，只允许在模拟器执行", isEmulator())
        val configurator = Configurator.getInstance()
        val originalUiAutomationFlags = configurator.uiAutomationFlags
        configurator.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val device = UiDevice.getInstance(instrumentation)
        // Instrumentation 与 UIAutomator 必须使用同一 flags，否则任一 UiDevice 操作都可能
        // 重新注册默认 UiAutomation 并压制被测的真实 AccessibilityService。
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val application = composeRule.activity.application as VolumeMapperApplication
        val graph = application.graph
        val coordinator = graph.mappingCoordinator
        val repository = graph.settingsRepository
        val packageName = composeRule.activity.packageName
        val accessibilityComponent =
            "$packageName/dev.spcdts.volumemapper.runtime.VolumeKeyAccessibilityService"

        val originalAccessibilityServices =
            shell(automation, "settings get secure enabled_accessibility_services")
                .trim()
                .takeUnless { it == "null" }
                .orEmpty()
        val originalAccessibilityEnabled =
            shell(automation, "settings get secure accessibility_enabled")
                .trim()
                .takeUnless { it == "null" }
        val originalSettings = repository.settings.value

        try {
            device.wakeUp()
            shell(automation, "wm dismiss-keyguard")
            grantNotificationPermissionIfNeeded(automation, packageName)

            // 外部 E2E runner 会先 pm clear；若单独重跑且已接受过披露，则仍验证已持久化状态。
            if (!repository.settings.value.disclosureAccepted) {
                composeRule.onNodeWithText("查看并同意").performClick()
                composeRule.onNodeWithText("无障碍 API 显著披露").assertIsDisplayed()
                composeRule.onNodeWithText("同意").assertIsNotEnabled()
                composeRule.onNode(isToggleable()).performClick()
                composeRule.onNodeWithText("同意").assertIsEnabled().performClick()
                composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
                    repository.settings.value.disclosureAccepted
                }
            }

            // 严格保持真实首启顺序：先完成显著披露，后启用系统无障碍组件。
            // 若组件在测试开始前已启用，重新安装/force-stop 后仅写入相同 setting 不会触发绑定；
            // 先做一次确定性的 disable → enable 转换，避免依赖设备残留状态。
            disableRequiredAccessibilityService(
                automation = automation,
                originalServices = originalAccessibilityServices,
                component = accessibilityComponent,
            )
            composeRule.waitUntil(ACCESSIBILITY_TIMEOUT_MILLIS) {
                !coordinator.runtime.value.isAccessibilityConnected
            }
            enableOnlyRequiredAccessibilityService(
                automation = automation,
                originalServices = originalAccessibilityServices,
                component = accessibilityComponent,
            )
            composeRule.waitUntil(ACCESSIBILITY_TIMEOUT_MILLIS) {
                coordinator.runtime.value.isAccessibilityConnected
            }

            val testSettings = repository.settings.value.copy(
                outputCurve = MappingCurve.linear(),
                keyConfig = repository.settings.value.keyConfig.copy(tapStep = 0.4),
                quantizationMode = VolumeQuantizationMode.INDEX,
                showSystemVolumeUi = false,
            )
            repository.updateCurve(testSettings.outputCurve)
            repository.updateKeyConfig(testSettings.keyConfig)
            repository.updateQuantizationMode(testSettings.quantizationMode)
            repository.updateShowSystemUi(testSettings.showSystemVolumeUi)
            repository.flushPendingWrite()
            awaitCoordinatorSettings(coordinator, testSettings)

            // 宿主机 E2E 只用本方法通过真实 UI 完成披露并写入确定性的 40% 线性配置。
            // 返回后 UiAutomation 已断开；宿主机随后通过 root sendevent 向模拟器 evdev
            // 注入硬件层事件，避免 adb shell input/UiAutomation 绕过 Accessibility input filter。
            if (prepareExternalJourney) return

            composeRule.onNodeWithText("启动映射", substring = true)
                .assertIsEnabled()
                .performClick()
            composeRule.waitUntil(CONTROLLER_TIMEOUT_MILLIS) {
                val runtime = coordinator.runtime.value
                runtime.isForegroundServiceRunning &&
                    runtime.isAccessibilityConnected &&
                    runtime.canInterceptKeys
            }

            shell(automation, "cmd statusbar expand-notifications")
            assertNotNull(
                "前台控制器通知不可见",
                device.wait(
                    Until.findObject(By.text("音量键映射正在运行")),
                    NOTIFICATION_TIMEOUT_MILLIS,
                ),
            )
            var stopAction = device.wait(
                Until.findObject(By.text("停止映射")),
                NOTIFICATION_ACTION_PROBE_MILLIS,
            )
            if (stopAction == null) {
                val expandAction = device.wait(
                    Until.findObject(By.res("com.android.systemui", "expand_button")),
                    NOTIFICATION_ACTION_PROBE_MILLIS,
                ) ?: device.wait(
                    Until.findObject(By.descContains("Expand")),
                    NOTIFICATION_ACTION_PROBE_MILLIS,
                )
                assertNotNull("折叠通知没有可用的展开控件", expandAction)
                checkNotNull(expandAction).click()
                stopAction = device.wait(
                    Until.findObject(By.text("停止映射")),
                    NOTIFICATION_TIMEOUT_MILLIS,
                )
            }
            assertNotNull("常驻通知缺少停止映射 action", stopAction)
            checkNotNull(stopAction).click()

            composeRule.waitUntil(CONTROLLER_TIMEOUT_MILLIS) {
                !coordinator.runtime.value.isForegroundServiceRunning &&
                    !coordinator.runtime.value.canInterceptKeys
            }
            assertFalse("通知停止后不应继续消费按键", coordinator.runtime.value.canInterceptKeys)
        } finally {
            MappingControllerService.stop(composeRule.activity)
            coordinator.disarm()
            if (!prepareExternalJourney) {
                repository.updateCurve(originalSettings.outputCurve)
                repository.updateKeyConfig(originalSettings.keyConfig)
                repository.updateQuantizationMode(originalSettings.quantizationMode)
                repository.updateShowSystemUi(originalSettings.showSystemVolumeUi)
                repository.flushPendingWrite()
            }
            restoreAccessibilitySettings(
                automation = automation,
                originalServices = originalAccessibilityServices,
                originalEnabled = originalAccessibilityEnabled,
            )
            configurator.uiAutomationFlags = originalUiAutomationFlags
            // pm revoke 会终止目标进程，也会连带杀死当前 instrumentation runner。
            // 测试入口在每轮前 pm clear，通知权限不需要在 runner 内撤销。
            shell(automation, "input keyevent KEYCODE_BACK")
        }
    }

    private fun enableOnlyRequiredAccessibilityService(
        automation: UiAutomation,
        originalServices: String,
        component: String,
    ) {
        val services = originalServices
            .split(':')
            .filter { it.isNotBlank() }
            .plus(component)
            .distinct()
            .joinToString(":")
        shell(
            automation,
            "settings put secure enabled_accessibility_services $services",
        )
        shell(automation, "settings put secure accessibility_enabled 1")
    }

    private fun disableRequiredAccessibilityService(
        automation: UiAutomation,
        originalServices: String,
        component: String,
    ) {
        val remainingServices = originalServices
            .split(':')
            .filter { it.isNotBlank() && it != component }
            .distinct()
        if (remainingServices.isEmpty()) {
            shell(automation, "settings delete secure enabled_accessibility_services")
            shell(automation, "settings put secure accessibility_enabled 0")
        } else {
            shell(
                automation,
                "settings put secure enabled_accessibility_services ${remainingServices.joinToString(":")}",
            )
            shell(automation, "settings put secure accessibility_enabled 1")
        }
    }

    private fun restoreAccessibilitySettings(
        automation: UiAutomation,
        originalServices: String,
        originalEnabled: String?,
    ) {
        if (originalServices.isBlank()) {
            shell(automation, "settings delete secure enabled_accessibility_services")
        } else {
            shell(
                automation,
                "settings put secure enabled_accessibility_services $originalServices",
            )
        }
        if (originalEnabled == null) {
            shell(automation, "settings delete secure accessibility_enabled")
        } else {
            shell(
                automation,
                "settings put secure accessibility_enabled $originalEnabled",
            )
        }
    }

    private fun grantNotificationPermissionIfNeeded(
        automation: UiAutomation,
        packageName: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private fun shell(automation: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun awaitCoordinatorSettings(
        coordinator: MappingCoordinator,
        expected: VolumeMapperSettings,
    ) {
        val settingsField = MappingCoordinator::class.java
            .getDeclaredField("settings")
            .apply { isAccessible = true }
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            settingsField.get(coordinator) == expected
        }
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        val product = Build.PRODUCT.lowercase(Locale.ROOT)
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            product.startsWith("sdk")
    }

    private companion object {
        const val ACCESSIBILITY_TIMEOUT_MILLIS = 10_000L
        const val CONTROLLER_TIMEOUT_MILLIS = 10_000L
        const val SETTINGS_TIMEOUT_MILLIS = 5_000L
        const val NOTIFICATION_TIMEOUT_MILLIS = 5_000L
        const val NOTIFICATION_ACTION_PROBE_MILLIS = 1_500L
        const val ARG_PREPARE_EXTERNAL = "e2ePrepareOnly"
    }
}
