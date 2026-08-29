package dev.spcdts.volumemapper

import android.media.AudioManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.spcdts.volumemapper.core.FixedVolumePresets
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.FixedVolumeRequestState
import dev.spcdts.volumemapper.runtime.MappingCoordinator
import dev.spcdts.volumemapper.ui.CurveEditorTestTags
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class FixedVolumePresetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var graph: AppGraph
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var originalSettings: VolumeMapperSettings

    @Before
    fun captureSettings() {
        graph = (composeRule.activity.application as VolumeMapperApplication).graph
        settingsRepository = graph.settingsRepository
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            settingsRepository.hasLoadedInitialSettings
        }
        originalSettings = settingsRepository.settings.value
    }

    @After
    fun restoreSettings() {
        runBlocking { settingsRepository.replaceSettingsAndAwait(originalSettings) }
    }

    @Test
    fun presetSheetAddsAndDeletesContinuouslyWithoutClosing() {
        val snapshot = refreshAdjustableSnapshot()
        assumeTrue(
            "至少需要两个媒体音量档位验证连续添加",
            snapshot.range.maxIndex - snapshot.range.minIndex >= 1,
        )
        replaceSettings(
            originalSettings.copy(fixedVolumePresets = FixedVolumePresets()),
        )

        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_ADD)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SHEET).assertIsDisplayed()

        val first = presetCandidate()
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_CONFIRM)
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            first in settingsRepository.settings.value.fixedVolumePresets.indices
        }
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.presetDelete(first))
            .assertIsDisplayed()

        val second = presetCandidate()
        assertTrue("连续添加应选择另一个未占用 index", second != first)
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_CONFIRM)
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            settingsRepository.settings.value.fixedVolumePresets.indices ==
                listOf(first, second).sorted()
        }
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SHEET).assertIsDisplayed()

        composeRule.onNodeWithTag(VolumeMapperTestTags.presetDelete(first))
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil(SETTINGS_TIMEOUT_MILLIS) {
            settingsRepository.settings.value.fixedVolumePresets.indices == listOf(second)
        }
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.presetDelete(second))
            .assertIsDisplayed()
    }

    @Test
    fun presetWorksWithMappingAndAccessibilityOffThenHighlightsObservedIndex() {
        val audioManager = composeRule.activity.getSystemService(AudioManager::class.java)
        val originalIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        try {
            assumeFalse("固定音量设备不支持此集成测试", audioManager.isVolumeFixed)
            assumeTrue(
                "通话或通信模式下不应执行媒体固定值测试",
                audioManager.mode == AudioManager.MODE_NORMAL,
            )
            val snapshot = refreshAdjustableSnapshot()
            val targetIndex = alternativeIndex(snapshot)
            assumeNotNull(targetIndex)
            val target = checkNotNull(targetIndex)

            val testSettings = originalSettings.copy(
                fixedVolumePresets = FixedVolumePresets(listOf(target)),
                showSystemVolumeUi = false,
            )
            replaceSettings(testSettings)
            awaitCoordinatorSettings(graph.mappingCoordinator, testSettings)

            if (graph.mappingCoordinator.runtime.value.isForegroundServiceRunning) {
                composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
                    .assertIsDisplayed()
                    .performClick()
            }
            composeRule.waitUntil(RUNTIME_TIMEOUT_MILLIS) {
                !graph.mappingCoordinator.runtime.value.isForegroundServiceRunning
            }
            graph.mappingCoordinator.setAccessibilityConnected(false)
            composeRule.waitUntil(RUNTIME_TIMEOUT_MILLIS) {
                val runtime = graph.mappingCoordinator.runtime.value
                !runtime.isForegroundServiceRunning &&
                    !runtime.isAccessibilityConnected &&
                    !runtime.canInterceptKeys
            }
            composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.Off,
                ),
            )
            assertFalse(graph.mappingCoordinator.runtime.value.isAccessibilityConnected)

            composeRule.onNodeWithTag(VolumeMapperTestTags.presetButton(target))
                .performScrollTo()
                .assertIsEnabled()
                .performClick()

            composeRule.waitUntil(VOLUME_TIMEOUT_MILLIS) {
                val request = graph.mappingCoordinator.fixedVolumeRequestState.value
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == target &&
                    graph.mappingCoordinator.runtime.value.snapshot?.currentIndex == target &&
                    request is FixedVolumeRequestState.Applied &&
                    request.requestedIndex == target &&
                    request.observedIndex == target
            }

            composeRule.onNodeWithTag(VolumeMapperTestTags.presetButton(target)).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    composeRule.activity.getString(R.string.fixed_volume_current),
                ),
            )
            val chartState = composeRule
                .onNodeWithTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
            assertTrue(
                "曲线当前音量语义应同步到固定值",
                chartState.contains(
                    composeRule.activity.getString(R.string.curve_state_current_volume, target),
                ),
            )
            assertFalse("固定值成功不应启用按键拦截", graph.mappingCoordinator.runtime.value.canInterceptKeys)
        } finally {
            val currentMin = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val currentMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val restoredIndex = originalIndex.coerceIn(currentMin, currentMax)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoredIndex, 0)
            composeRule.waitUntil(VOLUME_TIMEOUT_MILLIS) {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == restoredIndex
            }
            assertEquals(
                "测试结束后必须恢复媒体音量",
                restoredIndex,
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            )
        }
    }

    private fun refreshAdjustableSnapshot(): RouteVolumeSnapshot {
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(RUNTIME_TIMEOUT_MILLIS) {
            val runtime = graph.mappingCoordinator.runtime.value
            runtime.snapshot != null && runtime.isMediaContextSafe
        }
        val runtime = graph.mappingCoordinator.runtime.value
        val snapshot = checkNotNull(runtime.snapshot)
        assumeFalse("当前输出报告固定音量", runtime.isVolumeFixed)
        assumeTrue(
            "当前输出没有可调媒体音量范围",
            snapshot.range.minIndex < snapshot.range.maxIndex,
        )
        return snapshot
    }

    private fun alternativeIndex(snapshot: RouteVolumeSnapshot): Int? {
        val current = snapshot.currentIndex
        return when {
            current < snapshot.range.maxIndex -> current + 1
            current > snapshot.range.minIndex -> current - 1
            else -> null
        }
    }

    private fun presetCandidate(): Int = composeRule
        .onNodeWithTag(VolumeMapperTestTags.PRESET_VALUE)
        .fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription]
        .toInt()

    private fun replaceSettings(settings: VolumeMapperSettings) {
        runBlocking { settingsRepository.replaceSettingsAndAwait(settings) }
        composeRule.waitForIdle()
    }

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
        const val SETTINGS_TIMEOUT_MILLIS = 5_000L
        const val RUNTIME_TIMEOUT_MILLIS = 10_000L
        const val VOLUME_TIMEOUT_MILLIS = 5_000L
    }
}
