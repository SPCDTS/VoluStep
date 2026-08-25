package dev.spcdts.volumemapper

import android.Manifest
import android.app.LocaleManager
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器专用的真实系统链路 E2E。
 *
 * 与 [VolumeKeyAudioIntegrationTest] 不同，本测试会真正绑定 AccessibilityService、启动
 * specialUse FGS，并核验应用不声明通知权限时控制器仍能运行。测试会修改 secure
 * accessibility settings，因此通过 emulator guard 禁止在真机执行。
 *
 * Instrumentation 自身也通过 UiAutomation 占用无障碍通道，不能可靠证明系统按键被目标
 * AccessibilityService 过滤。完整按键和应用主开关停止阶段由 scripts/emulator-e2e-test.ps1
 * 在 runner 退出后执行，避免 Android 17 上 UiAutomation 与真实服务并存时的点击死锁。
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RealSystemVolumeE2eTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun disclosureAccessibilityControllerAndSilentForegroundStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val prepareExternalJourney = arguments.getString(ARG_PREPARE_EXTERNAL) == "true"
        val requestedLocale = arguments.getString(ARG_LOCALE)
        assumeTrue("本测试会修改 secure settings，只允许在模拟器执行", isEmulator())
        useApplicationLocale(requestedLocale)
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
        if (prepareExternalJourney) {
            instrumentation.sendStatus(
                STATUS_RESOURCE_CONTRACT,
                Bundle().apply {
                    putString(
                        STATUS_MASTER_SWITCH_DESCRIPTION,
                        appString(
                            R.string.master_switch_content_description,
                            appString(R.string.app_name),
                        ),
                    )
                    putString(
                        STATUS_NOTIFICATION_TITLE,
                        appString(R.string.controller_notification_title),
                    )
                },
            )
        }

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
            assertFalse(
                "静默前台服务不应声明通知权限",
                requestedPermissions(packageName).contains(Manifest.permission.POST_NOTIFICATIONS),
            )

            // 外部 E2E runner 会先 pm clear；若单独重跑且已接受过披露，则仍验证已持久化状态。
            if (!repository.settings.value.disclosureAccepted) {
                composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH).performClick()
                composeRule.onNodeWithText(appString(R.string.accessibility_disclosure_title))
                    .assertIsDisplayed()
                composeRule.onNodeWithText(appString(R.string.action_agree))
                    .assertIsNotEnabled()
                composeRule.onNodeWithText(appString(R.string.action_cancel)).performClick()
                composeRule.waitForIdle()
                assertFalse(
                    "取消披露不能保存同意或启动控制器",
                    repository.settings.value.disclosureAccepted ||
                        coordinator.runtime.value.isForegroundServiceRunning,
                )

                composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH).performClick()
                composeRule.onNodeWithText(appString(R.string.accessibility_disclosure_title))
                    .assertIsDisplayed()
                composeRule.onNodeWithText(appString(R.string.action_agree))
                    .assertIsNotEnabled()
                composeRule
                    .onNodeWithText(appString(R.string.accessibility_disclosure_consent))
                    .performClick()
                composeRule.onNodeWithText(appString(R.string.action_agree))
                    .assertIsEnabled()
                    .performClick()
                composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
                    repository.settings.value.disclosureAccepted
                }
                composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
                    !composeRule.activity.hasWindowFocus()
                }
                shell(automation, "input keyevent KEYCODE_BACK")
                composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
                    composeRule.activity.hasWindowFocus()
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

            assertFalse(
                "用户点击前不应自动启动前台控制器",
                coordinator.runtime.value.isForegroundServiceRunning,
            )
            composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
                .assertIsEnabled()
                .performClick()
            composeRule.waitUntil(CONTROLLER_TIMEOUT_MILLIS) {
                val runtime = coordinator.runtime.value
                runtime.isForegroundServiceRunning &&
                    runtime.isAccessibilityConnected &&
                    runtime.canInterceptKeys
            }

            MappingControllerService.stop(composeRule.activity)

            composeRule.waitUntil(CONTROLLER_TIMEOUT_MILLIS) {
                !coordinator.runtime.value.isForegroundServiceRunning &&
                    !coordinator.runtime.value.canInterceptKeys
            }
            assertFalse("控制器停止后不应继续消费按键", coordinator.runtime.value.canInterceptKeys)
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

    @Suppress("DEPRECATION")
    private fun requestedPermissions(packageName: String): List<String> =
        composeRule.activity.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

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

    private fun appString(resourceId: Int, vararg arguments: Any): String =
        composeRule.activity.getString(resourceId, *arguments)

    private fun useApplicationLocale(languageTag: String?) {
        if (languageTag.isNullOrBlank()) return
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "宿主 E2E 的应用语言参数只支持 Android 13 及以上版本"
        }
        val requestedLocales = LocaleList.forLanguageTags(languageTag)
        composeRule.runOnUiThread {
            composeRule.activity
                .getSystemService(LocaleManager::class.java)
                .applicationLocales = requestedLocales
        }
        val requestedLanguage = Locale.forLanguageTag(languageTag).language
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            composeRule.activity.resources.configuration.locales[0].language == requestedLanguage
        }
        composeRule.waitForIdle()
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
        const val ARG_LOCALE = "e2eLocale"
        const val STATUS_RESOURCE_CONTRACT = 2
        const val STATUS_MASTER_SWITCH_DESCRIPTION = "e2eMasterSwitchDescription"
        const val STATUS_NOTIFICATION_TITLE = "e2eNotificationTitle"
    }
}
