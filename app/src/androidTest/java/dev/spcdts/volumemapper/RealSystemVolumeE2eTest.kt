package dev.spcdts.volumemapper

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.spcdts.volumemapper.core.StepVolumeMap
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.MappingControllerService
import dev.spcdts.volumemapper.runtime.MappingCoordinator
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器专用的真实系统链路 E2E。
 *
 * 与 [VolumeKeyAudioIntegrationTest] 不同，本测试会真正绑定 AccessibilityService、启动
 * specialUse FGS，并核验常驻通知及其停止 action 的系统注册信息。测试会修改 secure
 * accessibility settings，因此通过 emulator guard 禁止在真机执行。
 *
 * Instrumentation 自身也通过 UiAutomation 占用无障碍通道，不能可靠证明系统按键被目标
 * AccessibilityService 过滤。完整按键和 SystemUI 通知点击阶段由 scripts/emulator-e2e-test.ps1
 * 在 runner 退出后执行，避免 Android 17 上 UiAutomation 与真实服务并存时的点击死锁。
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
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val application = composeRule.activity.application as VolumeMapperApplication
        val graph = application.graph
        val coordinator = graph.mappingCoordinator
        val repository = graph.settingsRepository
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            repository.hasLoadedInitialSettings
        }
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
            shell(automation, "input keyevent KEYCODE_WAKEUP")
            shell(automation, "wm dismiss-keyguard")
            grantNotificationPermissionIfNeeded(automation, packageName)

            // 外部 E2E runner 会先 pm clear；若单独重跑且已接受过披露，则仍验证已持久化状态。
            if (!repository.settings.value.disclosureAccepted) {
                composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH).performClick()
                composeRule.onNodeWithText("无障碍 API 显著披露").assertIsDisplayed()
                composeRule.onNodeWithText("同意").assertIsNotEnabled()
                composeRule
                    .onNodeWithText("我理解上述用途与按键冲突，并同意继续")
                    .performClick()
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

            val routeRange = checkNotNull(coordinator.runtime.value.snapshot).range
            val routeSpan = routeRange.maxIndex - routeRange.minIndex
            check(routeSpan >= 10) {
                "媒体音量档位少于 10，无法配置可区分系统默认步长的 E2E 映射"
            }
            val initialOffset = routeSpan / 3
            val mappedUpOffset = ((initialOffset.toDouble() / routeSpan + 0.4) * routeSpan + 0.5)
                .toInt()
            val testSettings = repository.settings.value.copy(
                // K=5 的按键位置仍均匀；两个内部控制点固定在整数按键位置 2、3。
                // 外部 E2E 从 1/3 档位起步：一次 UP 到约 +40%，再一次 DOWN 回原档。
                outputMap = StepVolumeMap(
                    basisSpan = routeSpan,
                    pressCount = 5,
                    pressPositions = listOf(0, 2, 3, 5),
                    offsets = listOf(0, initialOffset, mappedUpOffset, routeSpan),
                ),
                showSystemVolumeUi = false,
            )
            repository.updateOutputMap(testSettings.outputMap)
            repository.updateKeyConfig(testSettings.keyConfig)
            repository.updateShowSystemUi(testSettings.showSystemVolumeUi)
            repository.flushPendingWrite()
            awaitCoordinatorSettings(coordinator, testSettings)

            // 宿主机 E2E 只用本方法通过真实 UI 完成披露并写入确定性的 40% 离散档位。
            // 返回后 UiAutomation 已断开；宿主机随后通过 root sendevent 向模拟器 evdev
            // 注入硬件层事件，避免 adb shell input/UiAutomation 绕过 Accessibility input filter。
            if (prepareExternalJourney) return

            composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
                .assertIsEnabled()
                .performClick()
            composeRule.waitUntil(CONTROLLER_TIMEOUT_MILLIS) {
                val runtime = coordinator.runtime.value
                runtime.isForegroundServiceRunning &&
                    runtime.isAccessibilityConnected &&
                    runtime.canInterceptKeys
            }

            val notificationDump = shell(automation, "dumpsys notification --noredact")
            val notificationLines = notificationDump.lines()
            val recordStart = notificationLines.indexOfFirst { line ->
                line.contains("NotificationRecord(") &&
                    line.contains("pkg=$packageName ") &&
                    line.contains(" id=$CONTROLLER_NOTIFICATION_ID ")
            }
            assertTrue(
                "前台控制器通知不可见",
                recordStart >= 0,
            )
            val recordEnd = if (recordStart >= 0) {
                (recordStart + 1 until notificationLines.size).firstOrNull { lineIndex ->
                    notificationLines[lineIndex].contains("NotificationRecord(")
                } ?: notificationLines.size
            } else {
                0
            }
            val activeNotificationRecord = if (recordStart >= 0) {
                notificationLines.subList(recordStart, recordEnd).joinToString("\n")
            } else {
                ""
            }
            assertTrue(
                "前台控制器通知标题不正确",
                activeNotificationRecord.contains(
                    "android.title=String (音量键映射正在运行)",
                ),
            )
            assertTrue(
                "常驻通知缺少停止映射 action",
                activeNotificationRecord.contains("\"停止映射\" -> PendingIntent"),
            )
            MappingControllerService.stop(composeRule.activity)

            composeRule.waitUntil(CONTROLLER_TIMEOUT_MILLIS) {
                !coordinator.runtime.value.isForegroundServiceRunning &&
                    !coordinator.runtime.value.canInterceptKeys
            }
            assertFalse("通知停止后不应继续消费按键", coordinator.runtime.value.canInterceptKeys)
        } finally {
            runCleanupSteps(
                { MappingControllerService.stop(composeRule.activity) },
                { coordinator.disarm() },
                {
                    if (!prepareExternalJourney) {
                        runBlocking { repository.replaceSettingsAndAwait(originalSettings) }
                    }
                },
                {
                    restoreAccessibilitySettings(
                        automation = automation,
                        originalServices = originalAccessibilityServices,
                        originalEnabled = originalAccessibilityEnabled,
                    )
                },
                {
                    // pm revoke 会终止目标进程，也会连带杀死当前 instrumentation runner。
                    // 测试入口在每轮前 pm clear，通知权限不需要在 runner 内撤销。
                    shell(automation, "input keyevent KEYCODE_BACK")
                },
            )
        }
    }

    private fun runCleanupSteps(vararg steps: () -> Unit) {
        var firstFailure: Throwable? = null
        steps.forEach { step ->
            runCatching(step).onFailure { failure ->
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
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
        const val ARG_PREPARE_EXTERNAL = "e2ePrepareOnly"
        const val CONTROLLER_NOTIFICATION_ID = 4107
    }
}
