package dev.spcdts.volumemapper

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.spcdts.volumemapper.core.StepVolumeMap
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.MappingCoordinator
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class VolumeKeyAudioIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun completeVolumeUpGestureChangesRealMediaIndex() {
        composeRule.waitForIdle()
        val application = composeRule.activity.application as VolumeMapperApplication
        val graph = application.graph
        val coordinator = graph.mappingCoordinator
        val repository = graph.settingsRepository
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            repository.hasLoadedInitialSettings
        }
        val audioManager = composeRule.activity.getSystemService(AudioManager::class.java)
        val originalSettings = repository.settings.value
        val originalIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        var activeDownEvent: KeyEvent? = null

        try {
            assumeFalse("固定音量设备不支持此集成测试", audioManager.isVolumeFixed)
            assumeTrue(
                "通话或通信模式下不应接管媒体音量键",
                audioManager.mode == AudioManager.MODE_NORMAL,
            )
            assumeTrue(
                "当前音频后端无法取得输出路由",
                graph.volumeBackend.snapshot().isSuccess,
            )

            // MainActivity 保持可见；这里直接模拟两个系统组件已连接，不启动真实无障碍服务。
            coordinator.setAccessibilityConnected(true)
            coordinator.onForegroundServiceStarted()
            coordinator.arm()
            composeRule.waitUntil(READY_TIMEOUT_MILLIS) {
                coordinator.runtime.value.canInterceptKeys
            }

            val readySnapshot = checkNotNull(coordinator.runtime.value.snapshot)
            val indexSpan = readySnapshot.range.maxIndex - readySnapshot.range.minIndex
            assumeTrue("媒体音量档位过少，无法配置自由 X 集成映射", indexSpan >= 10)

            val initialOffset = indexSpan / 3
            val mappedUpOffset = ((initialOffset.toDouble() / indexSpan + 0.4) * indexSpan + 0.5)
                .toInt()
            val integerPressMap = StepVolumeMap(
                basisSpan = indexSpan,
                pressCount = 5,
                pressPositions = listOf(0, 2, 3, 5),
                offsets = listOf(0, initialOffset, mappedUpOffset, indexSpan),
            )
            val boundMap = integerPressMap.bind(readySnapshot.range)
            val testSettings = originalSettings.copy(
                outputMap = integerPressMap,
                showSystemVolumeUi = false,
            )
            repository.updateOutputMap(testSettings.outputMap)
            repository.updateKeyConfig(testSettings.keyConfig)
            repository.updateShowSystemUi(testSettings.showSystemVolumeUi)
            awaitCoordinatorSettings(coordinator, testSettings)

            val initialIndex = boundMap.indices[2]
            val expectedIndex = boundMap.indices[3]
            val initialWrite = graph.volumeBackend.setMediaVolume(
                index = initialIndex,
                showSystemUi = false,
            )
            assertTrue(
                "可见 Activity 下设置测试初始媒体音量失败：${initialWrite.exceptionOrNull()}",
                initialWrite.isSuccess,
            )
            composeRule.waitUntil(VOLUME_TIMEOUT_MILLIS) {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == initialIndex
            }

            coordinator.refreshSnapshot()
            composeRule.waitUntil(READY_TIMEOUT_MILLIS) {
                val runtime = coordinator.runtime.value
                runtime.canInterceptKeys &&
                    runtime.snapshot?.currentIndex == initialIndex &&
                    runtime.expectedIndex == initialIndex
            }

            val downTime = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP,
                0,
            )
            activeDownEvent = downEvent
            val downConsumed = coordinator.handleAccessibilityKey(downEvent)
            assertTrue("初始 DOWN 应被映射控制器消费", downConsumed)

            // Accessibility 回调必须快速返回。DOWN/UP 命令由同一 Channel 保序；若在两者之间
            // 等待异步写入，持续重组可能让 2 秒 lost-UP watchdog 合法清掉 owner。
            val upEvent = keyUpFor(downEvent)
            assertSameGesture(downEvent, upEvent)
            val upConsumed = coordinator.handleAccessibilityKey(upEvent)
            assertTrue("同一按键身份的 UP 应作为完整手势被消费", upConsumed)
            activeDownEvent = null

            composeRule.waitUntil(VOLUME_TIMEOUT_MILLIS) {
                val runtime = coordinator.runtime.value
                val actualIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                actualIndex == expectedIndex &&
                    runtime.expectedIndex == expectedIndex &&
                    runtime.consecutiveWriteFailures == 0
            }
        } finally {
            // 若断言在 DOWN 与 UP 之间失败，仍先排空 owner，再恢复全局音量和应用状态。
            runCleanupSteps(
                {
                    activeDownEvent?.let { downEvent ->
                        coordinator.handleAccessibilityKey(keyUpFor(downEvent))
                    }
                },
                { coordinator.disarm() },
                { coordinator.onForegroundServiceStopped() },
                { coordinator.setAccessibilityConnected(false) },
                { runBlocking { repository.replaceSettingsAndAwait(originalSettings) } },
                {
                    val currentMin = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                    val currentMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val restoredIndex = originalIndex.coerceIn(currentMin, currentMax)
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        restoredIndex,
                        0,
                    )
                    composeRule.waitUntil(VOLUME_TIMEOUT_MILLIS) {
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == restoredIndex
                    }
                    assertEquals(
                        "测试结束后必须恢复媒体音量",
                        restoredIndex,
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                    )
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

    private fun keyUpFor(downEvent: KeyEvent): KeyEvent =
        KeyEvent.changeTimeRepeat(
            KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP),
            max(SystemClock.uptimeMillis(), downEvent.downTime + 1L),
            0,
        )

    private fun assertSameGesture(downEvent: KeyEvent, upEvent: KeyEvent) {
        assertEquals(KeyEvent.ACTION_DOWN, downEvent.action)
        assertEquals(KeyEvent.ACTION_UP, upEvent.action)
        assertEquals(downEvent.downTime, upEvent.downTime)
        assertTrue("UP eventTime 不得早于 DOWN", upEvent.eventTime >= downEvent.eventTime)
        assertEquals(downEvent.deviceId, upEvent.deviceId)
        assertEquals(downEvent.keyCode, upEvent.keyCode)
    }

    /**
     * SettingsRepository 是立即更新、Coordinator 是 actor 异步应用。测试只读私有快照用于同步，
     * 不修改 runtime，也不以固定 sleep 猜测 actor 处理时机。
     */
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

    private companion object {
        const val READY_TIMEOUT_MILLIS = 10_000L
        const val SETTINGS_TIMEOUT_MILLIS = 5_000L
        const val VOLUME_TIMEOUT_MILLIS = 5_000L
    }
}
