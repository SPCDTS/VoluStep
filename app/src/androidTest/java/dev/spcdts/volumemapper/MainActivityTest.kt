package dev.spcdts.volumemapper

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import dev.spcdts.volumemapper.core.StepVolumeMap
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.ui.CurveEditorGeometry
import dev.spcdts.volumemapper.ui.CurveEditorTestTags
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
import java.util.Locale
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
    private var originalApplicationLocales: LocaleList? = null

    @Before
    fun captureSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            originalApplicationLocales = composeRule.activity
                .getSystemService(LocaleManager::class.java)
                .applicationLocales
        }
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        val repository = graph.settingsRepository
        composeRule.waitUntil(10_000L) { repository.hasLoadedInitialSettings }
        settingsRepository = repository
        originalSettings = repository.settings.value
    }

    @After
    fun restoreSettings() {
        runCleanupSteps(
            ::dismissOpenDialogsForCleanup,
            {
                if (::settingsRepository.isInitialized && ::originalSettings.isInitialized) {
                    runBlocking { settingsRepository.replaceSettingsAndAwait(originalSettings) }
                }
            },
            ::restoreApplicationLocales,
        )
    }

    @Test
    fun singlePageShowsCoreControlsLargeChartAndCurrentVolumeSemantics() {
        val launchMap = originalSettings.outputMap
        val launchPointIndex = launchMap.controlPointCount / 2
        waitForCurveState(
            appString(R.string.curve_state_selected_point, launchPointIndex + 1),
            appString(
                R.string.curve_state_x,
                launchMap.pressPositionAt(launchPointIndex),
            ),
        )

        useApplicationLocale("en-US")
        replaceSettings(testSettings(disclosureAccepted = false))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals("en", composeRule.activity.resources.configuration.locales[0].language)
        }
        assertEquals("VoluStep", appString(R.string.app_name))
        composeRule.onNodeWithTag(VolumeMapperTestTags.SCREEN_MAIN).assertIsDisplayed()
        composeRule.onNodeWithText(appString(R.string.app_name)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(VolumeMapperTestTags.BRAND_CONTROL_BAR)
            .assertCountEquals(1)
        composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
            .assertIsDisplayed()
            .assert(isToggleable())
            .assert(hasClickAction())
        assertEquals(
            appString(
                R.string.master_switch_content_description,
                appString(R.string.app_name),
            ),
            contentDescription(VolumeMapperTestTags.MASTER_SWITCH),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
            .assertIsDisplayed()
        assertEquals(
            appString(R.string.curve_control_points),
            contentDescription(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT),
        )
        val screenBounds = composeRule.onNodeWithTag(VolumeMapperTestTags.SCREEN_MAIN)
            .fetchSemanticsNode()
            .boundsInRoot
        val chartBounds = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue(
            "图表应尽量占满主界面的可用宽度",
            chartBounds.width >= screenBounds.width * 0.84f,
        )
        assertTrue(
            "图表高度应随屏幕扩大，同时在小屏保持足够的编辑空间",
            chartBounds.height >= minOf(screenBounds.height * 0.35f, 280f * density),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            appString(R.string.curve_button_presses),
            contentDescription(CurveEditorTestTags.PRESS_COUNT_INPUT),
        )
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SECTION)
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

        val snapshot = checkNotNull(graph.mappingCoordinator.runtime.value.snapshot)
        val currentIndex = snapshot.currentIndex
        val markerDescription = contentDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
        assertTrue(markerDescription.contains(appString(R.string.curve_chart_description)))
        assertTrue(
            "当前音量水平线必须向无障碍服务暴露实际 index",
            markerDescription.contains(
                appString(R.string.curve_current_volume_line, currentIndex),
            ),
        )
        val markerState = stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
        val routeSpan = snapshot.range.maxIndex - snapshot.range.minIndex
        val selectedDisplayIndex = snapshot.range.minIndex +
            (testMap().offsets[2].toDouble() * routeSpan / testMap().basisSpan).roundToInt()
        assertTrue(markerState.contains(appString(R.string.curve_state_summary, 18, 5)))
        assertTrue(markerState.contains(appString(R.string.curve_state_selected_point, 3)))
        assertTrue(markerState.contains(appString(R.string.curve_state_x, 8)))
        assertTrue(
            markerState.contains(
                appString(R.string.curve_state_index, selectedDisplayIndex),
            ),
        )
    }

    @Test
    fun overflowProvidesInAppPrivacyAndVersionInformation() {
        useApplicationLocale("zh-CN")

        composeRule.onNodeWithTag(VolumeMapperTestTags.APP_MENU)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRIVACY_MENU_ITEM)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRIVACY_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(appString(R.string.privacy_on_device_title)).assertIsDisplayed()
        composeRule.onNodeWithText(appString(R.string.action_ok)).performClick()

        composeRule.onNodeWithTag(VolumeMapperTestTags.APP_MENU).performClick()
        composeRule.onNodeWithTag(VolumeMapperTestTags.ABOUT_MENU_ITEM)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(VolumeMapperTestTags.ABOUT_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(
            appString(R.string.about_version, BuildConfig.VERSION_NAME),
        ).assertIsDisplayed()

        // Locale restoration recreates the Activity. Detach the Dialog window first so teardown
        // never asks Compose/Espresso to settle a recreation while the old About dialog is alive.
        composeRule.onNodeWithText(appString(R.string.action_ok))
            .assertIsDisplayed()
            .performClick()
        composeRule.onAllNodesWithTag(VolumeMapperTestTags.ABOUT_DIALOG)
            .assertCountEquals(0)
    }

    @Test
    fun selectedSegmentInsertionAndSelectedPointDeletionFormAnExactRoundTrip() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val canvas = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot

        // 点击第 2 条线段后，+ 必须只在这条线段的整数中点插入；线段态不能删除。
        val segmentStart = curvePointOnCanvas(
            map = initialMap,
            pointIndex = 1,
            canvasWidth = canvasBounds.width,
            canvasHeight = canvasBounds.height,
        )
        val segmentEnd = curvePointOnCanvas(
            map = initialMap,
            pointIndex = 2,
            canvasWidth = canvasBounds.width,
            canvasHeight = canvasBounds.height,
        )
        canvas.performTouchInput { click(midpoint(segmentStart, segmentEnd)) }
        waitForCurveState(
            appString(R.string.curve_state_selected_segment, 2),
            appString(R.string.curve_state_x_range, 3, 8),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsEnabled()
            .performClick()
        val insertedMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 3, 5, 8, 13, 18),
            offsets = listOf(0, 3, 5, 8, 18, 30),
        )
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == insertedMap }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 3),
            appString(R.string.curve_state_x, 5),
        )
        assertIntegerGrid(insertedMap)

        // 明确点击新增的内部点后，- 只能删除这个点；结果应精确恢复原图。
        val insertedPoint = curvePointOnCanvas(
            map = insertedMap,
            pointIndex = 2,
            canvasWidth = canvasBounds.width,
            canvasHeight = canvasBounds.height,
        )
        canvas.performTouchInput { click(insertedPoint) }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 3),
            appString(R.string.curve_state_x, 5),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsEnabled()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == initialMap }
        waitForCurveState(
            appString(R.string.curve_state_selected_segment, 2),
            appString(R.string.curve_state_x_range, 3, 8),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsEnabled()
    }

    @Test
    fun selectedPointInsertionUsesItsRightSegmentAndLastPointUsesItsLeftSegment() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val canvas = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot

        // 普通控制点的 + 作用于其右侧线段，而不是左侧线段或整张曲线。
        val ordinaryPoint = curvePointOnCanvas(
            map = initialMap,
            pointIndex = 1,
            canvasWidth = canvasBounds.width,
            canvasHeight = canvasBounds.height,
        )
        canvas.performTouchInput { click(ordinaryPoint) }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 2),
            appString(R.string.curve_state_x, 3),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
            .assertIsEnabled()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsEnabled()
            .performClick()
        val insertedOnRight = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 3, 5, 8, 13, 18),
            offsets = listOf(0, 3, 5, 8, 18, 30),
        )
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == insertedOnRight }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 3),
            appString(R.string.curve_state_x, 5),
        )
        assertIntegerGrid(insertedOnRight)

        // 最后一个控制点没有右侧线段，+ 明确回退到左侧线段。
        replaceSettings(testSettings(outputMap = initialMap))
        val lastPointIndex = initialMap.controlPointCount - 1
        val lastPoint = curvePointOnCanvas(
            map = initialMap,
            pointIndex = lastPointIndex,
            canvasWidth = canvasBounds.width,
            canvasHeight = canvasBounds.height,
        )
        canvas.performTouchInput { click(lastPoint) }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, lastPointIndex + 1),
            appString(R.string.curve_state_x, 18),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsEnabled()
            .performClick()
        val insertedOnLeft = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 3, 8, 13, 15, 18),
            offsets = listOf(0, 3, 8, 18, 23, 30),
        )
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == insertedOnLeft }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 5),
            appString(R.string.curve_state_x, 15),
        )
        assertIntegerGrid(insertedOnLeft)
    }

    @Test
    fun pressCountChangesPreservePointSelectionAndStopAtControlCapacity() {
        val canvas = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot

        // 改 K 只重投影整数 x；点选择必须保持在同一个控制点上。
        val selectionStableMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 5,
            pressPositions = listOf(0, 1, 5),
            offsets = listOf(0, 5, 30),
        )
        replaceSettings(testSettings(outputMap = selectionStableMap))
        canvas.performTouchInput {
            click(
                curvePointOnCanvas(
                    selectionStableMap,
                    1,
                    canvasBounds.width,
                    canvasBounds.height,
                ),
            )
        }
        waitForCurveState(appString(R.string.curve_state_selected_point, 2))
        val pressCountInput = composeRule
            .onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
        pressCountInput.performTextReplacement("2")
        pressCountInput.performImeAction()
        composeRule.waitUntil { settingsRepository.settings.value.outputMap.pressCount == 2 }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 2),
            appString(R.string.curve_state_x, 1),
        )

        // K 不能小于 P - 1；最密整数网格同时禁止继续插入。
        val denseMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 5,
            pressPositions = (0..5).toList(),
            offsets = listOf(0, 2, 5, 10, 18, 30),
        )
        replaceSettings(testSettings(outputMap = denseMap))
        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_DECREMENT)
            .performScrollTo()
            .assertIsNotEnabled()
        canvas.performTouchInput {
            click(
                midpoint(
                    curvePointOnCanvas(
                        denseMap,
                        2,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                    curvePointOnCanvas(
                        denseMap,
                        3,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                ),
            )
        }
        waitForCurveState(
            appString(R.string.curve_state_selected_segment, 3),
            appString(R.string.curve_state_x_range, 2, 3),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsNotEnabled()
        assertIntegerGrid(denseMap)
    }

    @Test
    fun draggingAControlPointHardSnapsBothAxes() {
        val initialMap = testMap().withOffset(controlPointIndex = 2, requestedOffset = 5)
        replaceSettings(testSettings(outputMap = initialMap))

        val canvas = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        val plotLeft = CurveEditorGeometry.PLOT_LEFT_PADDING_DP * density
        val plotRight = bounds.width - CurveEditorGeometry.PLOT_RIGHT_PADDING_DP * density
        val plotTop = CurveEditorGeometry.PLOT_TOP_PADDING_DP * density
        val plotBottom = bounds.height - CurveEditorGeometry.PLOT_BOTTOM_PADDING_DP * density
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        val movedPoint = 2
        val rawTargetPress = 10.4
        val targetX = rawTargetPress / initialMap.pressCount.toDouble()
        val targetOffset = 14
        val start = curvePointOnCanvas(
            map = initialMap,
            pointIndex = movedPoint,
            canvasWidth = bounds.width,
            canvasHeight = bounds.height,
        )
        val end = Offset(
            x = plotLeft + targetX.toFloat() * plotWidth,
            y = plotBottom - targetOffset.toFloat() / initialMap.basisSpan * plotHeight,
        )

        // Android 媒体路由通常只有 15 档，而编辑基准是 30。纯横拖不能把基准
        // offset 5 经显示 index 3 反投影成 6。
        val horizontalEnd = Offset(x = end.x, y = start.y)
        canvas.performTouchInput {
            down(start)
            moveTo(horizontalEnd, delayMillis = 450L)
            up()
        }
        composeRule.waitUntil {
            settingsRepository.settings.value.outputMap.pressPositionAt(movedPoint) == 10
        }
        assertEquals(
            "纯横向拖动必须保持原始 y，不能经过显示档位反投影后漂移",
            initialMap.offsets[movedPoint],
            settingsRepository.settings.value.outputMap.offsets[movedPoint],
        )
        waitForCurveState(appString(R.string.curve_state_x, 10))
        replaceSettings(testSettings(outputMap = initialMap))
        waitForCurveState(
            appString(
                R.string.curve_state_x,
                initialMap.pressPositionAt(movedPoint),
            ),
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
        assertEquals(
            "任何横向位置都必须硬吸附到最近整数按键位",
            10,
            movedMap.pressPositionAt(movedPoint),
        )
        assertEquals(
            "y 必须吸附到整数 Audio volume index",
            targetOffset,
            movedMap.offsets[movedPoint],
        )
        assertEquals(initialMap.pressCount, movedMap.pressCount)
        assertEquals(initialMap.controlPointCount, movedMap.controlPointCount)
        assertIntegerGrid(movedMap)

        // 拖过右侧相邻点时夹在相邻整数位置，而不是越点或重新保存小数。
        val movedStart = curvePointOnCanvas(
            map = movedMap,
            pointIndex = movedPoint,
            canvasWidth = bounds.width,
            canvasHeight = bounds.height,
        )
        val beyondRightNeighbour = Offset(
            x = plotLeft + 17f / movedMap.pressCount.toFloat() * plotWidth,
            y = movedStart.y,
        )
        canvas.performTouchInput {
            down(movedStart)
            moveTo(beyondRightNeighbour, delayMillis = 450L)
            up()
        }
        composeRule.waitUntil {
            settingsRepository.settings.value.outputMap.pressPositionAt(movedPoint) == 12
        }
        val clampedMap = settingsRepository.settings.value.outputMap
        assertEquals(12, clampedMap.pressPositionAt(movedPoint))
        assertIntegerGrid(clampedMap)
        waitForCurveState(appString(R.string.curve_state_x, 12))
    }

    @Test
    fun chartAccessibilityActionsFollowPointAndSegmentSelection() {
        useApplicationLocale("zh-CN")
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals("zh", composeRule.activity.resources.configuration.locales[0].language)
        }
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
        val pointActionLabels = listOf(
            R.string.curve_action_select_previous_point,
            R.string.curve_action_select_next_point,
            R.string.curve_action_select_previous_segment,
            R.string.curve_action_select_next_segment,
            R.string.curve_action_move_point_left,
            R.string.curve_action_move_point_right,
            R.string.curve_action_move_point_up,
            R.string.curve_action_move_point_down,
            R.string.curve_action_delete_point,
            R.string.curve_action_insert_point,
        ).map { appString(it) }
        assertTrue(labels.containsAll(pointActionLabels))
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains(appString(R.string.curve_state_vertical_hint)),
        )

        // Canvas 是一个低层自绘控件；线段选择、插入和删除必须有等价的无障碍动作。
        performCurveAction(R.string.curve_action_select_next_segment)
        waitForCurveState(
            appString(R.string.curve_state_selected_segment, 3),
            appString(R.string.curve_state_x_range, 8, 13),
        )
        val segmentLabels = chart.fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .map { it.label }
        assertTrue(segmentLabels.contains(appString(R.string.curve_action_insert_point)))
        assertTrue(!segmentLabels.contains(appString(R.string.curve_action_delete_point)))
        assertTrue(!segmentLabels.contains(appString(R.string.curve_action_move_point_left)))
        assertTrue(!segmentLabels.contains(appString(R.string.curve_action_move_point_up)))

        performCurveAction(R.string.curve_action_insert_point)
        val accessibilityInsertedMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 3, 8, 10, 13, 18),
            offsets = listOf(0, 3, 8, 12, 18, 30),
        )
        composeRule.waitUntil {
            settingsRepository.settings.value.outputMap == accessibilityInsertedMap
        }
        waitForCurveState(
            appString(R.string.curve_state_selected_point, 4),
            appString(R.string.curve_state_x, 10),
        )
        performCurveAction(R.string.curve_action_delete_point)
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == initialMap }
        waitForCurveState(
            appString(R.string.curve_state_selected_segment, 3),
            appString(R.string.curve_state_x_range, 8, 13),
        )
    }

    @Test
    fun chartAccessibilityMovesSelectedPointByIntegerPressAndRouteIndex() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        waitForCurveState(appString(R.string.curve_state_selected_point, 3))

        performCurveAction(R.string.curve_action_select_next_point)
        waitForCurveState(appString(R.string.curve_state_selected_point, 4))
        performCurveAction(R.string.curve_action_select_previous_point)
        waitForCurveState(appString(R.string.curve_state_selected_point, 3))

        performCurveAction(R.string.curve_action_move_point_right)
        composeRule.waitUntil { settingsRepository.settings.value.outputMap != initialMap }
        val horizontallyMoved = settingsRepository.settings.value.outputMap
        assertEquals(
            initialMap.pressPositionAt(2) + 1,
            horizontallyMoved.pressPositionAt(2),
        )
        assertIntegerGrid(horizontallyMoved)

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
        performCurveAction(R.string.curve_action_move_point_up)
        val verticallyMoved = settingsRepository.settings.value.outputMap
        val afterDisplayIndex = snapshot.range.minIndex + (
            verticallyMoved.offsets[2].toDouble() * routeSpan / verticallyMoved.basisSpan
            ).roundToInt()
        assertEquals(expectedDisplayIndex, afterDisplayIndex)

        if (routeSpan in 1 until initialMap.basisSpan) {
            val yLockedMap = StepVolumeMap(
                basisSpan = initialMap.basisSpan,
                pressCount = initialMap.pressCount,
                pressPositions = initialMap.pressPositions,
                offsets = listOf(0, 1, 2, 3, initialMap.basisSpan),
            )
            replaceSettings(testSettings(outputMap = yLockedMap))
            val xBefore = yLockedMap.pressPositionAt(2)
            performCurveAction(R.string.curve_action_move_point_right)
            composeRule.waitUntil { settingsRepository.settings.value.outputMap != yLockedMap }
            assertEquals(
                "横向无障碍步进不应被纵轴可用空间阻断",
                xBefore + 1,
                settingsRepository.settings.value.outputMap.pressPositionAt(2),
            )
        }
    }

    private fun curvePointOnCanvas(
        map: StepVolumeMap,
        pointIndex: Int,
        canvasWidth: Float,
        canvasHeight: Float,
    ): Offset {
        val density = composeRule.activity.resources.displayMetrics.density
        val plotLeft = CurveEditorGeometry.PLOT_LEFT_PADDING_DP * density
        val plotRight = canvasWidth - CurveEditorGeometry.PLOT_RIGHT_PADDING_DP * density
        val plotTop = CurveEditorGeometry.PLOT_TOP_PADDING_DP * density
        val plotBottom = canvasHeight - CurveEditorGeometry.PLOT_BOTTOM_PADDING_DP * density
        val snapshot = (composeRule.activity.application as VolumeMapperApplication)
            .graph
            .mappingCoordinator
            .runtime
            .value
            .snapshot
        val normalizedY = snapshot?.range?.let { range ->
            val routeSpan = range.maxIndex - range.minIndex
            if (routeSpan > 0) {
                val displayedIndex = range.minIndex + (
                    map.offsets[pointIndex].toDouble() * routeSpan / map.basisSpan
                    ).roundToInt()
                (displayedIndex - range.minIndex).toFloat() / routeSpan.toFloat()
            } else {
                null
            }
        } ?: (map.offsets[pointIndex].toFloat() / map.basisSpan.toFloat())
        return Offset(
            x = plotLeft + map.normalizedXAt(pointIndex).toFloat() * (plotRight - plotLeft),
            y = plotBottom - normalizedY * (plotBottom - plotTop),
        )
    }

    private fun midpoint(first: Offset, second: Offset): Offset = Offset(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f,
    )

    private fun testSettings(
        outputMap: StepVolumeMap = testMap(),
        disclosureAccepted: Boolean = originalSettings.disclosureAccepted,
    ): VolumeMapperSettings = originalSettings.copy(
        outputMap = outputMap,
        disclosureAccepted = disclosureAccepted,
    )

    private fun testMap(): StepVolumeMap = StepVolumeMap(
        basisSpan = 30,
        pressCount = 18,
        pressPositions = listOf(0, 3, 8, 13, 18),
        offsets = listOf(0, 3, 8, 18, 30),
    )

    private fun replaceSettings(settings: VolumeMapperSettings) {
        runBlocking { settingsRepository.replaceSettingsAndAwait(settings) }
        composeRule.waitForIdle()
    }

    private fun assertIntegerGrid(map: StepVolumeMap) {
        assertEquals(0, map.pressPositions.first())
        assertEquals(map.pressCount, map.pressPositions.last())
        assertTrue(map.pressPositions.zipWithNext().all { (left, right) -> right > left })
        assertTrue(map.offsets.zipWithNext().all { (left, right) -> right > left })
        assertTrue(map.controlPointCount <= map.pressCount + 1)
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

    private fun appString(
        @StringRes resourceId: Int,
        vararg arguments: Any,
    ): String = composeRule.activity.getString(resourceId, *arguments)

    private fun waitForCurveState(vararg expectedClauses: String) {
        composeRule.waitUntil(5_000L) {
            val state = stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
            expectedClauses.all(state::contains)
        }
    }

    private fun useApplicationLocale(languageTag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val requestedLocales = LocaleList.forLanguageTags(languageTag)
        composeRule.runOnUiThread {
            composeRule.activity
                .getSystemService(LocaleManager::class.java)
                .applicationLocales = requestedLocales
        }
        val requestedLanguage = Locale.forLanguageTag(languageTag).language
        composeRule.waitUntil(10_000L) {
            composeRule.activity.resources.configuration.locales[0].language == requestedLanguage
        }
        composeRule.waitForIdle()
    }

    private fun restoreApplicationLocales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val locales = originalApplicationLocales ?: return
        val localeManager = composeRule.activity.applicationContext
            .getSystemService(LocaleManager::class.java)
        composeRule.runOnUiThread {
            localeManager.applicationLocales = locales
        }
        composeRule.waitUntil(10_000L) {
            localeManager.applicationLocales == locales
        }
        composeRule.waitForIdle()
    }

    private fun dismissOpenDialogsForCleanup() {
        listOf(
            VolumeMapperTestTags.PRIVACY_DIALOG,
            VolumeMapperTestTags.ABOUT_DIALOG,
        ).forEach { dialogTag ->
            if (composeRule.onAllNodesWithTag(dialogTag).fetchSemanticsNodes().isEmpty()) {
                return@forEach
            }
            composeRule.onNodeWithText(appString(R.string.action_ok)).performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithTag(dialogTag).fetchSemanticsNodes().isEmpty()
            }
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

    private fun performCurveAction(@StringRes labelResourceId: Int) {
        val label = appString(labelResourceId)
        val action = composeRule.onNodeWithTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
        composeRule.runOnUiThread { assertTrue("自定义操作应成功：$label", action.action()) }
        composeRule.waitForIdle()
    }
}
