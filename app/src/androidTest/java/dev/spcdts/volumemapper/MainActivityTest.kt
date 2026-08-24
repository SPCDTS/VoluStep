package dev.spcdts.volumemapper

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import dev.spcdts.volumemapper.core.KeyMappingConfig
import dev.spcdts.volumemapper.core.StepVolumeMap
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.ui.CurveEditorTestTags
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var originalSettings: VolumeMapperSettings

    @Before
    fun captureSettings() {
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        val repository = graph.settingsRepository
        composeRule.waitUntil(10_000L) { repository.hasLoadedInitialSettings }
        settingsRepository = repository
        originalSettings = repository.settings.value
    }

    @After
    fun restoreSettings() {
        runBlocking { settingsRepository.replaceSettingsAndAwait(originalSettings) }
    }

    @Test
    fun approvedSinglePageShowsCoreControlsAndCurrentVolumeSemantics() {
        replaceSettings(testSettings(disclosureAccepted = false))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        composeRule.onNodeWithTag(VolumeMapperTestTags.SCREEN_MAIN).assertIsDisplayed()
        composeRule.onNodeWithText("音量映射").assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
            .assertIsDisplayed()
            .assert(isToggleable())
            .assert(hasClickAction())
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS).assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_VALUE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.DEVICE_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()

        listOf(
            VolumeMapperTestTags.NAV_CONTROL,
            VolumeMapperTestTags.NAV_CURVE,
            VolumeMapperTestTags.NAV_DIAGNOSTICS,
            VolumeMapperTestTags.SCREEN_DIAGNOSTICS,
            CurveEditorTestTags.UNDO,
            CurveEditorTestTags.REDO,
            CurveEditorTestTags.DETAILS_TOGGLE,
        ).forEach { removedTag ->
            composeRule.onAllNodesWithTag(removedTag).assertCountEquals(0)
        }

        val currentIndex = checkNotNull(graph.mappingCoordinator.runtime.value.snapshot).currentIndex
        val markerDescription = contentDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
        assertTrue(
            "当前音量水平线必须向无障碍服务暴露实际 index",
            markerDescription.contains("当前音量水平线 $currentIndex"),
        )

        // 未接受显著披露时，主开关只能进入授权流程，不能在测试中误启动全局控制器。
        composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("无障碍 API 显著披露").assertIsDisplayed()
    }

    @Test
    fun pAndKSteppersRemainIndependent() {
        replaceSettings(testSettings())

        assertStepperValue(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT, 5)
        assertStepperValue(CurveEditorTestTags.PRESS_COUNT_INPUT, 18)

        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil {
            settingsRepository.settings.value.outputMap.controlPointCount == 6
        }
        assertEquals(18, settingsRepository.settings.value.outputMap.pressCount)
        assertStepperValue(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT, 6)
        assertStepperValue(CurveEditorTestTags.PRESS_COUNT_INPUT, 18)

        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_DECREMENT)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil { settingsRepository.settings.value.outputMap.pressCount == 17 }
        assertEquals(6, settingsRepository.settings.value.outputMap.controlPointCount)
        assertStepperValue(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT, 6)
        assertStepperValue(CurveEditorTestTags.PRESS_COUNT_INPUT, 17)

        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }
        val routeSpan = checkNotNull(graph.mappingCoordinator.runtime.value.snapshot).range.let {
            it.maxIndex - it.minIndex
        }
        if (routeSpan in 1 until initialBasisSpan) {
            val routeLimitedPointCount = routeSpan + 1
            val denseMap = testMap().resampleControlPoints(routeLimitedPointCount)
            replaceSettings(testSettings(outputMap = denseMap))
            composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            composeRule.waitUntil {
                settingsRepository.settings.value.outputMap.controlPointCount ==
                    routeLimitedPointCount + 1
            }
        }
    }

    @Test
    fun draggingAControlPointMovesBothFreeXAndIntegerIndex() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))

        val canvas = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        val plotLeft = 36f * density
        val plotRight = bounds.width - 10f * density
        val plotTop = 24f * density
        val plotBottom = bounds.height - 28f * density
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        val movedPoint = 2
        val targetX = 10.0 / initialMap.pressCount.toDouble()
        val targetOffset = 14
        val start = Offset(
            x = plotLeft + initialMap.normalizedXAt(movedPoint).toFloat() * plotWidth,
            y = plotBottom -
                initialMap.offsets[movedPoint].toFloat() / initialMap.basisSpan * plotHeight,
        )
        val end = Offset(
            x = plotLeft + targetX.toFloat() * plotWidth,
            y = plotBottom - targetOffset.toFloat() / initialMap.basisSpan * plotHeight,
        )

        canvas.performTouchInput {
            down(start)
            moveTo(end, delayMillis = 450L)
            cancel()
        }
        composeRule.waitForIdle()
        assertEquals("取消拖动不能提交或留下幽灵状态", initialMap, settingsRepository.settings.value.outputMap)

        canvas.performTouchInput {
            down(start)
            moveTo(end, delayMillis = 450L)
            up()
        }
        composeRule.waitUntil {
            settingsRepository.settings.value.outputMap != initialMap
        }

        val movedMap = settingsRepository.settings.value.outputMap
        assertTrue(
            "控制点应能脱离均匀 P 位置并横向移动",
            movedMap.normalizedXAt(movedPoint) > initialMap.normalizedXAt(movedPoint) + 0.05,
        )
        assertTrue(
            "靠近均匀按键位置时 x 应吸附",
            abs(movedMap.normalizedXAt(movedPoint) - targetX) < 1e-4,
        )
        assertEquals(
            "y 必须吸附到整数 Audio volume index",
            targetOffset,
            movedMap.offsets[movedPoint],
        )
        assertEquals(initialMap.pressCount, movedMap.pressCount)
        assertEquals(initialMap.controlPointCount, movedMap.controlPointCount)
    }

    @Test
    fun chartAccessibilityActionsSelectAndMoveTheCurrentControlPoint() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        val chart = composeRule.onNodeWithTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
            .performScrollTo()
            .assertIsDisplayed()
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue(
            "当前音量语义必须覆盖整张图表，而不是 1dp 占位节点",
            chart.fetchSemanticsNode().boundsInRoot.width > 200f * density,
        )
        val labels = chart.fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .map { it.label }
        assertTrue(labels.contains("选择上一个控制点"))
        assertTrue(labels.contains("选择下一个控制点"))
        assertTrue(labels.contains("控制点左移一个按键位置"))
        assertTrue(labels.contains("控制点右移一个按键位置"))
        assertTrue(labels.contains("控制点上移一个可表示档位"))
        assertTrue(labels.contains("控制点下移一个可表示档位"))
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("上下操作按当前路由可表示档位移动"),
        )

        performCurveAction("选择下一个控制点")
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 4 个控制点"),
        )
        performCurveAction("选择上一个控制点")
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 个控制点"),
        )

        performCurveAction("控制点右移一个按键位置")
        composeRule.waitUntil { settingsRepository.settings.value.outputMap != initialMap }
        val horizontallyMoved = settingsRepository.settings.value.outputMap
        assertEquals(
            initialMap.normalizedXAt(2) + 1.0 / initialMap.pressCount.toDouble(),
            horizontallyMoved.normalizedXAt(2),
            1e-6,
        )

        val snapshot = checkNotNull(graph.mappingCoordinator.runtime.value.snapshot)
        val routeSpan = snapshot.range.maxIndex - snapshot.range.minIndex
        val beforeDisplayIndex = snapshot.range.minIndex + (
            horizontallyMoved.offsets[2].toDouble() * routeSpan / horizontallyMoved.basisSpan
            ).roundToInt()
        val maximumOffset = horizontallyMoved.basisSpan -
            (horizontallyMoved.controlSegmentCount - 2)
        val expectedDisplayIndex = (horizontallyMoved.offsets[2] + 1..maximumOffset)
            .map { candidateOffset ->
                snapshot.range.minIndex + (
                    candidateOffset.toDouble() * routeSpan / horizontallyMoved.basisSpan
                    ).roundToInt()
            }
            .first { candidateIndex -> candidateIndex > beforeDisplayIndex }
        performCurveAction("控制点上移一个可表示档位")
        val verticallyMoved = settingsRepository.settings.value.outputMap
        val afterDisplayIndex = snapshot.range.minIndex + (
            verticallyMoved.offsets[2].toDouble() * routeSpan / verticallyMoved.basisSpan
            ).roundToInt()
        assertEquals(expectedDisplayIndex, afterDisplayIndex)

        if (routeSpan in 1 until initialMap.basisSpan) {
            val yLockedMap = StepVolumeMap(
                basisSpan = initialMap.basisSpan,
                pressCount = initialMap.pressCount,
                normalizedXs = initialMap.normalizedXs,
                offsets = listOf(0, 1, 2, 3, initialMap.basisSpan),
            )
            replaceSettings(testSettings(outputMap = yLockedMap))
            val xBefore = yLockedMap.normalizedXAt(2)
            performCurveAction("控制点右移一个按键位置")
            composeRule.waitUntil { settingsRepository.settings.value.outputMap != yLockedMap }
            assertEquals(
                "横向无障碍步进不应被纵轴可用空间阻断",
                xBefore + 1.0 / yLockedMap.pressCount.toDouble(),
                settingsRepository.settings.value.outputMap.normalizedXAt(2),
                1e-6,
            )
        }
    }

    @Test
    fun longPressIntervalUsesTwentyMillisecondStepsAndSixtyToFiveHundredBounds() {
        replaceSettings(testSettings(holdStepIntervalMillis = 120L))
        assertLongPressInterval(120L)

        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_DECREMENT)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil {
            settingsRepository.settings.value.keyConfig.holdStepIntervalMillis == 100L
        }
        assertLongPressInterval(100L)

        replaceSettings(testSettings(holdStepIntervalMillis = 60L))
        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_DECREMENT)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_INCREMENT)
            .performClick()
        composeRule.waitUntil {
            settingsRepository.settings.value.keyConfig.holdStepIntervalMillis == 80L
        }
        assertLongPressInterval(80L)

        replaceSettings(testSettings(holdStepIntervalMillis = 500L))
        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_INCREMENT)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_DECREMENT)
            .performClick()
        composeRule.waitUntil {
            settingsRepository.settings.value.keyConfig.holdStepIntervalMillis == 480L
        }
        assertLongPressInterval(480L)
    }

    @Test
    fun deviceDetailsAreSecondaryAndCollapsible() {
        replaceSettings(testSettings())

        composeRule.onNodeWithTag(VolumeMapperTestTags.DEVICE_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag(VolumeMapperTestTags.DEVICE_DETAILS).assertCountEquals(0)

        composeRule.onNodeWithTag(VolumeMapperTestTags.DEVICE_TOGGLE).performClick()
        composeRule.onNodeWithTag(VolumeMapperTestTags.DEVICE_DETAILS)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(VolumeMapperTestTags.DEVICE_TOGGLE)
            .performScrollTo()
            .performClick()
        composeRule.onAllNodesWithTag(VolumeMapperTestTags.DEVICE_DETAILS).assertCountEquals(0)
    }

    private fun testSettings(
        outputMap: StepVolumeMap = testMap(),
        holdStepIntervalMillis: Long = 120L,
        disclosureAccepted: Boolean = originalSettings.disclosureAccepted,
    ): VolumeMapperSettings = originalSettings.copy(
        outputMap = outputMap,
        keyConfig = KeyMappingConfig(
            holdDelayMillis = originalSettings.keyConfig.holdDelayMillis,
            holdStepIntervalMillis = holdStepIntervalMillis,
        ),
        disclosureAccepted = disclosureAccepted,
    )

    private fun testMap(): StepVolumeMap = StepVolumeMap(
        basisSpan = 30,
        pressCount = 18,
        normalizedXs = listOf(0.0, 0.17, 0.42, 0.74, 1.0),
        offsets = listOf(0, 3, 8, 18, 30),
    )

    private val initialBasisSpan: Int
        get() = 30

    private fun replaceSettings(settings: VolumeMapperSettings) {
        runBlocking { settingsRepository.replaceSettingsAndAwait(settings) }
        composeRule.waitForIdle()
    }

    private fun assertStepperValue(tag: String, expected: Int) {
        composeRule.onNodeWithTag(tag).performScrollTo()
        assertEquals(expected.toString(), stateDescription(tag))
    }

    private fun assertLongPressInterval(expected: Long) {
        composeRule.onNodeWithTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_VALUE)
            .performScrollTo()
        assertEquals(
            "$expected 毫秒",
            stateDescription(VolumeMapperTestTags.LONG_PRESS_INTERVAL_VALUE),
        )
        assertEquals(expected, settingsRepository.settings.value.keyConfig.holdStepIntervalMillis)
    }

    private fun stateDescription(tag: String): String =
        composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]

    private fun contentDescription(tag: String): String =
        composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString(separator = "")

    private fun performCurveAction(label: String) {
        val action = composeRule.onNodeWithTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
        composeRule.runOnUiThread { assertTrue("自定义操作应成功：$label", action.action()) }
        composeRule.waitForIdle()
    }
}
