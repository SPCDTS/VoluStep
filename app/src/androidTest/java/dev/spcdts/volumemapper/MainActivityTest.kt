package dev.spcdts.volumemapper

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
import dev.spcdts.volumemapper.ui.CurveEditorTestTags
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
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
    fun singlePageShowsCoreControlsLargeChartAndCurrentVolumeSemantics() {
        replaceSettings(testSettings(disclosureAccepted = false))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        composeRule.onNodeWithTag(VolumeMapperTestTags.SCREEN_MAIN).assertIsDisplayed()
        composeRule.onNodeWithText("音量映射").assertIsDisplayed()
        composeRule.onNodeWithText("精细控制").assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.MASTER_SWITCH)
            .assertIsDisplayed()
            .assert(isToggleable())
            .assert(hasClickAction())
        assertEquals(
            "启用精细音量控制",
            contentDescription(VolumeMapperTestTags.MASTER_SWITCH),
        )
        composeRule.onNodeWithTag(VolumeMapperTestTags.CONTROLLER_STATUS_ACTION)
            .assert(hasClickAction())
        assertEquals(
            "查看授权说明",
            onClickLabel(VolumeMapperTestTags.CONTROLLER_STATUS_ACTION),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
            .assertIsDisplayed()
        assertEquals(
            "控制点数量",
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
            "按键次数",
            contentDescription(CurveEditorTestTags.PRESS_COUNT_INPUT),
        )
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
        val markerState = stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
        assertTrue(markerState.contains("按键次数 18，控制点数量 5"))
        assertTrue(markerState.contains("，x 8，index "))
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
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 2 条线段，x 3 到 8")
        }
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
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 个控制点，x 5"),
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
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 个控制点，x 5")
        }
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == initialMap }
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 2 条线段，x 3 到 8"),
        )
    }

    @Test
    fun controlPointButtonsDisableForEndpointsAndLocallyFullIntegerSegments() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val canvas = composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot

        // 两个端点固定，选中端点时不得删除，也不能在“点选择”状态插入。
        val firstPoint = curvePointOnCanvas(
            map = initialMap,
            pointIndex = 0,
            canvasWidth = canvasBounds.width,
            canvasHeight = canvasBounds.height,
        )
        canvas.performTouchInput { click(firstPoint) }
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 1 个控制点，x 0")
        }
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsNotEnabled()
        assertEquals(initialMap, settingsRepository.settings.value.outputMap)

        // 局部没有空闲整数 x 的线段不可插入，即使整张图仍有其他可用位置。
        val xFullSegmentMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 1, 8, 13, 18),
            offsets = listOf(0, 3, 8, 18, 30),
        )
        replaceSettings(testSettings(outputMap = xFullSegmentMap))
        canvas.performTouchInput {
            click(
                midpoint(
                    curvePointOnCanvas(
                        xFullSegmentMap,
                        0,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                    curvePointOnCanvas(
                        xFullSegmentMap,
                        1,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                ),
            )
        }
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 1 条线段，x 0 到 1")
        }
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsNotEnabled()

        // y 同样是严格整数网格；没有空闲整数 index 时也不能悄悄移动其他点来插入。
        val yFullSegmentMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 3, 8, 13, 18),
            offsets = listOf(0, 1, 8, 18, 30),
        )
        replaceSettings(testSettings(outputMap = yFullSegmentMap))
        canvas.performTouchInput {
            click(
                midpoint(
                    curvePointOnCanvas(
                        yFullSegmentMap,
                        0,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                    curvePointOnCanvas(
                        yFullSegmentMap,
                        1,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                ),
            )
        }
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 1 条线段，x 0 到 3")
        }
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsNotEnabled()

        // P 的产品上限是 16；即使选中线段仍有空闲整数 X/Y，也不得继续插入。
        val maximumPointMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = (0..14).toList() + 18,
            offsets = (0..14).toList() + 30,
        )
        replaceSettings(testSettings(outputMap = maximumPointMap))
        canvas.performTouchInput {
            click(
                midpoint(
                    curvePointOnCanvas(
                        maximumPointMap,
                        14,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                    curvePointOnCanvas(
                        maximumPointMap,
                        15,
                        canvasBounds.width,
                        canvasBounds.height,
                    ),
                ),
            )
        }
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 15 条线段，x 14 到 18")
        }
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
            .assertIsNotEnabled()
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
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 2 个控制点")
        }
        val pressCountInput = composeRule
            .onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
        pressCountInput.performTextReplacement("2")
        pressCountInput.performImeAction()
        composeRule.waitUntil { settingsRepository.settings.value.outputMap.pressCount == 2 }
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 2 个控制点，x 1"),
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
        composeRule.waitUntil {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 条线段，x 2 到 3")
        }
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
        val plotLeft = 56f * density
        val plotRight = bounds.width - 8f * density
        val plotTop = 14f * density
        val plotBottom = bounds.height - 30f * density
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
        composeRule.waitUntil(5_000L) {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("，x 10，")
        }
        replaceSettings(testSettings(outputMap = initialMap))
        composeRule.waitUntil(5_000L) {
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("，x ${initialMap.pressPositionAt(movedPoint)}，")
        }

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
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER).contains("，x 12，"),
        )
    }

    @Test
    fun chartAccessibilityActionsFollowPointAndSegmentSelection() {
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
        assertTrue(labels.contains("选择上一条线段"))
        assertTrue(labels.contains("选择下一条线段"))
        assertTrue(labels.contains("控制点左移一个按键位置"))
        assertTrue(labels.contains("控制点右移一个按键位置"))
        assertTrue(labels.contains("控制点上移一个可表示档位"))
        assertTrue(labels.contains("控制点下移一个可表示档位"))
        assertTrue(labels.contains("删除选中控制点"))
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("上下操作按当前路由可表示档位移动"),
        )

        // Canvas 是一个低层自绘控件；线段选择、插入和删除必须有等价的无障碍动作。
        performCurveAction("选择下一条线段")
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 条线段，x 8 到 13"),
        )
        val segmentLabels = chart.fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .map { it.label }
        assertTrue(segmentLabels.contains("在线段中插入控制点"))
        assertTrue(!segmentLabels.contains("删除选中控制点"))
        assertTrue(!segmentLabels.contains("控制点左移一个按键位置"))
        assertTrue(!segmentLabels.contains("控制点上移一个可表示档位"))

        performCurveAction("在线段中插入控制点")
        val accessibilityInsertedMap = StepVolumeMap(
            basisSpan = 30,
            pressCount = 18,
            pressPositions = listOf(0, 3, 8, 10, 13, 18),
            offsets = listOf(0, 3, 8, 12, 18, 30),
        )
        composeRule.waitUntil {
            settingsRepository.settings.value.outputMap == accessibilityInsertedMap
        }
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 4 个控制点，x 10"),
        )
        performCurveAction("删除选中控制点")
        composeRule.waitUntil { settingsRepository.settings.value.outputMap == initialMap }
        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 条线段，x 8 到 13"),
        )
    }

    @Test
    fun chartAccessibilityMovesSelectedPointByIntegerPressAndRouteIndex() {
        val initialMap = testMap()
        replaceSettings(testSettings(outputMap = initialMap))
        val graph = (composeRule.activity.application as VolumeMapperApplication).graph
        graph.mappingCoordinator.refreshSnapshot()
        composeRule.waitUntil(10_000L) { graph.mappingCoordinator.runtime.value.snapshot != null }

        assertTrue(
            stateDescription(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .contains("已选第 3 个控制点"),
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
                pressPositions = initialMap.pressPositions,
                offsets = listOf(0, 1, 2, 3, initialMap.basisSpan),
            )
            replaceSettings(testSettings(outputMap = yLockedMap))
            val xBefore = yLockedMap.pressPositionAt(2)
            performCurveAction("控制点右移一个按键位置")
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
        val plotLeft = 56f * density
        val plotRight = canvasWidth - 8f * density
        val plotTop = 14f * density
        val plotBottom = canvasHeight - 30f * density
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

    private fun onClickLabel(tag: String): String? =
        composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .label

    private fun performCurveAction(label: String) {
        val action = composeRule.onNodeWithTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
        composeRule.runOnUiThread { assertTrue("自定义操作应成功：$label", action.action()) }
        composeRule.waitForIdle()
    }
}
