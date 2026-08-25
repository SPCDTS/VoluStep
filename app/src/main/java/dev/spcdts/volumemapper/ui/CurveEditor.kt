package dev.spcdts.volumemapper.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.StepVolumeMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Piecewise-linear volume map editor.
 *
 * P authored control points are free on both axes. K + 1 short-press positions stay uniformly
 * distributed over the x axis. X softly snaps to those positions; y always snaps to an integer
 * Audio volume index.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MappingCurveEditor(
    outputMap: StepVolumeMap,
    snapshot: RouteVolumeSnapshot?,
    currentLogicalPosition: Double?,
    onMapCommitted: (StepVolumeMap) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorMap by remember { mutableStateOf(outputMap) }
    var selectedControlPoint by remember {
        mutableIntStateOf(
            (outputMap.controlPointCount / 2).coerceIn(outputMap.normalizedXs.indices),
        )
    }
    var gestureInProgress by remember { mutableStateOf(false) }
    var xSnapGuide by remember { mutableStateOf<Double?>(null) }
    var ySnapGuide by remember { mutableStateOf<Int?>(null) }
    var expectedCommittedMap by remember { mutableStateOf<StepVolumeMap?>(null) }
    var expectedCommitSourceMap by remember { mutableStateOf<StepVolumeMap?>(null) }

    val latestEditorMap by rememberUpdatedState(editorMap)
    val latestOutputMap by rememberUpdatedState(outputMap)
    val latestOnMapCommitted by rememberUpdatedState(onMapCommitted)
    val externalSourceToken = remember(outputMap) { Any() }
    val latestExternalSourceToken by rememberUpdatedState(externalSourceToken)

    LaunchedEffect(
        outputMap,
        gestureInProgress,
        expectedCommittedMap,
        expectedCommitSourceMap,
    ) {
        if (gestureInProgress) return@LaunchedEffect
        val expected = expectedCommittedMap
        val waitingForOwnCommit = expected != null &&
            outputMap == expectedCommitSourceMap &&
            outputMap != expected
        if (waitingForOwnCommit) return@LaunchedEffect

        expectedCommittedMap = null
        expectedCommitSourceMap = null
        if (outputMap != editorMap) {
            val previousX = editorMap.normalizedXAt(
                selectedControlPoint.coerceIn(editorMap.normalizedXs.indices),
            )
            editorMap = outputMap
            selectedControlPoint = outputMap.closestControlPointIndex(previousX)
        }
    }

    // Capture one immutable map for this composition. Draw callbacks may run while repository
    // state is changing; mixing a new map with control-point lists from the previous composition
    // can otherwise address a point that no longer exists.
    val renderedMap = editorMap
    val routeMinimum = snapshot?.range?.minIndex
    val routeMaximum = snapshot?.range?.maxIndex
    val routeSpan = if (routeMinimum != null && routeMaximum != null) {
        routeMaximum - routeMinimum
    } else {
        null
    }
    val displayMinimum = routeMinimum ?: 0
    val displayMaximum = routeMaximum ?: renderedMap.basisSpan
    val displaySpan = displayMaximum - displayMinimum
    val displayDenominator = displaySpan.coerceAtLeast(1)
    val editorReady = routeSpan?.let { it > 0 } ?: true

    fun displayIndexForOffset(map: StepVolumeMap, offset: Int): Int = if (routeSpan != null) {
        if (routeSpan == 0) {
            displayMinimum
        } else {
            displayMinimum + (
                offset.toDouble() * routeSpan.toDouble() / map.basisSpan.toDouble()
                ).roundToInt()
        }
    } else {
        offset
    }

    fun referenceOffsetForDisplayIndex(map: StepVolumeMap, displayIndex: Int): Int =
        if (routeSpan != null && routeSpan > 0) {
            (
                (displayIndex - displayMinimum).toDouble() * map.basisSpan.toDouble() /
                    routeSpan.toDouble()
                ).roundToInt().coerceIn(0, map.basisSpan)
        } else {
            (displayIndex - displayMinimum).coerceIn(0, map.basisSpan)
        }

    val displayedControlIndices = renderedMap.offsets.map { offset ->
        displayIndexForOffset(renderedMap, offset)
    }
    val currentDisplayIndex = snapshot?.currentIndex?.toDouble()
    val currentIndexLabel = snapshot?.currentIndex?.toString()
    val currentX = currentDisplayIndex?.let { index ->
        renderedMap.normalizedXForDisplayedIndex(displayedControlIndices, index)
    }
    val runtimePreview = renderedMap.bind(
        snapshot?.range ?: RouteVolumeRange(0, renderedMap.basisSpan),
    )

    val selectedIndex = selectedControlPoint.coerceIn(renderedMap.normalizedXs.indices)
    val maximumControlPointCount = maxOf(
        renderedMap.controlPointCount,
        min(MAXIMUM_CONTROL_POINT_COUNT, renderedMap.basisSpan + 1),
    )
    val density = LocalDensity.current
    val leftPaddingPx = with(density) { 38.dp.toPx() }
    val rightPaddingPx = with(density) { 12.dp.toPx() }
    val topPaddingPx = with(density) { 28.dp.toPx() }
    val bottomPaddingPx = with(density) { 28.dp.toPx() }
    val hitRadiusPx = with(density) { 22.dp.toPx() }
    val xSnapDistancePx = with(density) { 10.dp.toPx() }

    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val darkTheme = isSystemInDarkTheme()
    val snap = if (darkTheme) Color(0xFFE5A8C4) else Color(0xFF96516F)
    val current = if (darkTheme) Color(0xFF8ED4A5) else Color(0xFF2D7A4B)
    val currentContent = if (darkTheme) Color(0xFF14351F) else Color.White

    fun publish(next: StepVolumeMap, preferredX: Double) {
        if (next == editorMap) return
        editorMap = next
        selectedControlPoint = next.closestControlPointIndex(preferredX)
        latestOnMapCommitted(next)
    }

    fun moveSelectedControlPoint(
        pressDelta: Int = 0,
        displayIndexDelta: Int = 0,
    ): Boolean {
        val before = editorMap
        val pointIndex = selectedControlPoint.coerceIn(before.normalizedXs.indices)
        if (!editorReady || pointIndex == 0 || pointIndex == before.controlSegmentCount) {
            return false
        }

        val currentX = before.normalizedXAt(pointIndex)
        val requestedX = if (pressDelta == 0) {
            currentX
        } else {
            val candidate = currentX + pressDelta.toDouble() / before.pressCount.toDouble()
            val minimumX = before.normalizedXAt(pointIndex - 1) +
                StepVolumeMap.MINIMUM_CONTROL_X_SPACING
            val maximumX = before.normalizedXAt(pointIndex + 1) -
                StepVolumeMap.MINIMUM_CONTROL_X_SPACING
            if (candidate !in minimumX..maximumX) return false
            candidate
        }

        val currentOffset = before.offsets[pointIndex]
        val currentIndex = displayIndexForOffset(before, currentOffset)
        val requestedOffset = when {
            displayIndexDelta > 0 -> {
                val maximumOffset = before.basisSpan -
                    (before.controlSegmentCount - pointIndex)
                (currentOffset + 1..maximumOffset).firstOrNull { candidate ->
                    displayIndexForOffset(before, candidate) > currentIndex
                } ?: return false
            }

            displayIndexDelta < 0 -> {
                val minimumOffset = pointIndex
                (currentOffset - 1 downTo minimumOffset).firstOrNull { candidate ->
                    displayIndexForOffset(before, candidate) < currentIndex
                } ?: return false
            }

            else -> currentOffset
        }
        val after = before.moveControlPointPushing(
            controlPointIndex = pointIndex,
            requestedNormalizedX = requestedX,
            requestedOffset = requestedOffset,
        )
        if (after == before) return false
        val movedIndex = displayIndexForOffset(after, after.offsets[pointIndex])
        if (
            (displayIndexDelta > 0 && movedIndex <= currentIndex) ||
            (displayIndexDelta < 0 && movedIndex >= currentIndex)
        ) {
            return false
        }
        editorMap = after
        selectedControlPoint = pointIndex
        latestOnMapCommitted(after)
        return true
    }

    val selectedDisplayIndex = displayIndexForOffset(
        renderedMap,
        renderedMap.offsets[selectedIndex],
    )
    val chartCustomActions = listOf(
        CustomAccessibilityAction("选择上一个控制点") {
            if (selectedControlPoint <= 0) {
                false
            } else {
                selectedControlPoint -= 1
                true
            }
        },
        CustomAccessibilityAction("选择下一个控制点") {
            if (selectedControlPoint >= editorMap.controlSegmentCount) {
                false
            } else {
                selectedControlPoint += 1
                true
            }
        },
        CustomAccessibilityAction("控制点左移一个按键位置") {
            moveSelectedControlPoint(pressDelta = -1)
        },
        CustomAccessibilityAction("控制点右移一个按键位置") {
            moveSelectedControlPoint(pressDelta = 1)
        },
        CustomAccessibilityAction("控制点上移一个可表示档位") {
            moveSelectedControlPoint(displayIndexDelta = 1)
        },
        CustomAccessibilityAction("控制点下移一个可表示档位") {
            moveSelectedControlPoint(displayIndexDelta = -1)
        },
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompactCurveStepper(
            label = "控制点 P",
            value = renderedMap.controlPointCount,
            minimum = 2,
            maximum = maximumControlPointCount,
            enabled = editorReady,
            onValueChange = { requested ->
                val oldX = renderedMap.normalizedXAt(selectedIndex)
                publish(renderedMap.resampleControlPoints(requested), oldX)
            },
            valueTag = CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT,
            decrementTag = CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT,
            incrementTag = CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(224.dp)
                .testTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .semantics {
                    contentDescription = buildString {
                        append(
                            "音量映射图。横轴是均匀按键位置，纵轴是整数音量 index；" +
                                "可通过更多操作选择和移动控制点。",
                        )
                        snapshot?.let { append("当前音量水平线 ${it.currentIndex}。") }
                    }
                    stateDescription = buildString {
                        append("K ${renderedMap.pressCount}，P ${renderedMap.controlPointCount}")
                        append("，已选第 ${selectedIndex + 1} 个控制点")
                        append(
                            "，x ${(
                                renderedMap.normalizedXAt(selectedIndex) *
                                    renderedMap.pressCount
                                ).formatAxisPosition()}",
                        )
                        append("，index $selectedDisplayIndex")
                        append("，上下操作按当前路由可表示档位移动")
                        snapshot?.let { append("，当前音量 ${it.currentIndex}") }
                    }
                    customActions = chartCustomActions
                }
                .focusable(),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(CurveEditorTestTags.CANVAS)
                    .pointerInput(
                        editorReady,
                        renderedMap.basisSpan,
                        renderedMap.pressCount,
                        displayMinimum,
                        displayMaximum,
                    ) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!editorReady) return@awaitEachGesture
                            val startMap = latestEditorMap
                            val plotLeft = leftPaddingPx
                            val plotRight = size.width.toFloat() - rightPaddingPx
                            val plotTop = topPaddingPx
                            val plotBottom = size.height.toFloat() - bottomPaddingPx
                            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                            fun pointFor(map: StepVolumeMap, index: Int): Offset {
                                val displayedIndex = displayIndexForOffset(map, map.offsets[index])
                                val normalizedY =
                                    (displayedIndex - displayMinimum).toFloat() /
                                        displayDenominator.toFloat()
                                return Offset(
                                    x = plotLeft + map.normalizedXAt(index).toFloat() * plotWidth,
                                    y = plotBottom - normalizedY * plotHeight,
                                )
                            }

                            fun squaredDistance(left: Offset, right: Offset): Float {
                                val dx = left.x - right.x
                                val dy = left.y - right.y
                                return dx * dx + dy * dy
                            }

                            val hitRadiusSquared = hitRadiusPx * hitRadiusPx
                            val hitIndex = startMap.normalizedXs.indices
                                .minBy { squaredDistance(pointFor(startMap, it), down.position) }
                                .takeIf {
                                    squaredDistance(pointFor(startMap, it), down.position) <=
                                        hitRadiusSquared
                                }
                                ?: return@awaitEachGesture

                            selectedControlPoint = hitIndex
                            if (hitIndex == 0 || hitIndex == startMap.controlSegmentCount) {
                                return@awaitEachGesture
                            }
                            val currentX = startMap.normalizedXAt(hitIndex)
                            val canMoveX =
                                currentX > startMap.normalizedXAt(hitIndex - 1) +
                                StepVolumeMap.MINIMUM_CONTROL_X_SPACING ||
                                    currentX < startMap.normalizedXAt(hitIndex + 1) -
                                    StepVolumeMap.MINIMUM_CONTROL_X_SPACING
                            val minimumOffset = hitIndex
                            val maximumOffset = startMap.basisSpan -
                                (startMap.controlSegmentCount - hitIndex)
                            val canMoveY = startMap.offsets[hitIndex] > minimumOffset ||
                                startMap.offsets[hitIndex] < maximumOffset
                            if (!canMoveX && !canMoveY) return@awaitEachGesture

                            val gestureSourceMap = latestOutputMap
                            val gestureSourceToken = latestExternalSourceToken
                            gestureInProgress = true
                            down.consume()
                            val beforeGesture = startMap
                            var gestureMap = startMap
                            var completedWithNormalUp = false
                            try {
                                completedWithNormalUp = drag(down.id) { change ->
                                        val rawX = ((change.position.x - plotLeft) / plotWidth)
                                            .coerceIn(0f, 1f)
                                            .toDouble()
                                        val nearestPressX =
                                            (rawX * gestureMap.pressCount).roundToInt().toDouble() /
                                                gestureMap.pressCount.toDouble()
                                        val leftX = gestureMap.normalizedXAt(hitIndex - 1)
                                        val rightX = gestureMap.normalizedXAt(hitIndex + 1)
                                        val nearestPressIsMovable =
                                            nearestPressX > leftX +
                                            StepVolumeMap.MINIMUM_CONTROL_X_SPACING &&
                                                nearestPressX < rightX -
                                                StepVolumeMap.MINIMUM_CONTROL_X_SPACING
                                        val effectiveSnapDistancePx = min(
                                            xSnapDistancePx,
                                            plotWidth * 0.4f / gestureMap.pressCount.toFloat(),
                                        )
                                        val shouldSnapX = nearestPressIsMovable &&
                                            abs(nearestPressX - rawX) * plotWidth <=
                                            effectiveSnapDistancePx
                                        val requestedX = if (shouldSnapX) nearestPressX else rawX
                                        val rawDisplayIndex = displayMinimum +
                                            (plotBottom - change.position.y) / plotHeight *
                                            displaySpan.toFloat()
                                        val minimumDisplayIndex = displayIndexForOffset(
                                            gestureMap,
                                            gestureMap.offsets[hitIndex - 1],
                                        ) + 1
                                        val maximumDisplayIndex = displayIndexForOffset(
                                            gestureMap,
                                            gestureMap.offsets[hitIndex + 1],
                                        ) - 1
                                        val requestedDisplayIndex = if (
                                            minimumDisplayIndex <= maximumDisplayIndex
                                        ) {
                                            rawDisplayIndex.roundToInt().coerceIn(
                                                minimumDisplayIndex,
                                                maximumDisplayIndex,
                                            )
                                        } else {
                                            displayIndexForOffset(
                                                gestureMap,
                                                gestureMap.offsets[hitIndex],
                                            )
                                        }
                                        val requestedOffset = referenceOffsetForDisplayIndex(
                                            gestureMap,
                                            requestedDisplayIndex,
                                        )
                                        val moved = gestureMap.moveControlPointPushing(
                                            controlPointIndex = hitIndex,
                                            requestedNormalizedX = requestedX,
                                            requestedOffset = requestedOffset,
                                        )
                                        gestureMap = moved
                                        editorMap = moved
                                        selectedControlPoint = hitIndex
                                        val actualX = moved.normalizedXAt(hitIndex)
                                        xSnapGuide = nearestPressX.takeIf {
                                            shouldSnapX && abs(actualX - nearestPressX) < 1e-5
                                        }
                                        ySnapGuide = displayIndexForOffset(
                                            moved,
                                            moved.offsets[hitIndex],
                                        )
                                    change.consume()
                                }
                            } finally {
                                val sourceUnchanged =
                                    latestExternalSourceToken === gestureSourceToken
                                if (completedWithNormalUp && sourceUnchanged) {
                                    if (gestureMap != beforeGesture) {
                                        expectedCommitSourceMap = gestureSourceMap
                                        expectedCommittedMap = gestureMap
                                        latestOnMapCommitted(gestureMap)
                                    }
                                } else {
                                    val replacement = latestOutputMap
                                    val previousX = gestureMap.normalizedXAt(
                                        hitIndex.coerceIn(gestureMap.normalizedXs.indices),
                                    )
                                    expectedCommittedMap = null
                                    expectedCommitSourceMap = null
                                    editorMap = replacement
                                    selectedControlPoint =
                                        replacement.closestControlPointIndex(previousX)
                                }
                                gestureInProgress = false
                                xSnapGuide = null
                                ySnapGuide = null
                            }
                        }
                    },
            ) {
                val plotLeft = leftPaddingPx
                val plotRight = size.width - rightPaddingPx
                val plotTop = topPaddingPx
                val plotBottom = size.height - bottomPaddingPx
                val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                fun pointFor(normalizedX: Double, index: Double): Offset {
                    val normalizedY = ((index - displayMinimum) / displayDenominator.toDouble())
                        .coerceIn(0.0, 1.0)
                    return Offset(
                        x = plotLeft + normalizedX.toFloat() * plotWidth,
                        y = plotBottom - normalizedY.toFloat() * plotHeight,
                    )
                }

                val tickPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    color = tick.copy(alpha = 0.86f).toArgb()
                    textSize = 10.sp.toPx()
                    textAlign = AndroidPaint.Align.RIGHT
                }
                val axisFractions = listOf(0f, 1f / 3f, 2f / 3f, 1f)
                axisFractions.forEach { fraction ->
                    val y = plotBottom - fraction * plotHeight
                    drawLine(
                        color = grid.copy(alpha = if (fraction == 0f) 0.72f else 0.48f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val value = displayMinimum + (displaySpan * fraction).roundToInt()
                    drawContext.canvas.nativeCanvas.drawText(
                        value.toString(),
                        plotLeft - 7.dp.toPx(),
                        y - (tickPaint.ascent() + tickPaint.descent()) / 2f,
                        tickPaint,
                    )
                }

                val xTickPaint = AndroidPaint(tickPaint).apply {
                    textAlign = AndroidPaint.Align.CENTER
                }
                listOf(0f, 0.5f, 1f).forEachIndexed { tickIndex, fraction ->
                    val x = plotLeft + fraction * plotWidth
                    drawLine(
                        color = grid.copy(alpha = 0.65f),
                        start = Offset(x, plotBottom),
                        end = Offset(x, plotBottom + 4.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val value = when (tickIndex) {
                        0 -> "0"
                        1 -> (renderedMap.pressCount / 2.0).formatAxisPosition()
                        else -> renderedMap.pressCount.toString()
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        value,
                        x,
                        plotBottom + 17.dp.toPx(),
                        xTickPaint,
                    )
                }

                val controlPoints = displayedControlIndices.mapIndexed { index, displayIndex ->
                    pointFor(renderedMap.normalizedXAt(index), displayIndex.toDouble())
                }
                val area = Path().apply {
                    moveTo(controlPoints.first().x, plotBottom)
                    lineTo(controlPoints.first().x, controlPoints.first().y)
                    controlPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    lineTo(controlPoints.last().x, plotBottom)
                    close()
                }
                drawPath(area, color = primary.copy(alpha = 0.075f))

                ySnapGuide?.let { displayIndex ->
                    val guideY = pointFor(0.0, displayIndex.toDouble()).y
                    drawLine(
                        color = snap,
                        start = Offset(plotLeft, guideY),
                        end = Offset(plotRight, guideY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        ),
                    )
                }
                xSnapGuide?.let { normalizedX ->
                    val guideX = plotLeft + normalizedX.toFloat() * plotWidth
                    drawLine(
                        color = snap,
                        start = Offset(guideX, plotTop),
                        end = Offset(guideX, plotBottom),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        ),
                    )
                }

                val currentY = currentDisplayIndex?.let { pointFor(0.0, it).y }
                if (currentY != null) {
                    drawLine(
                        color = current.copy(alpha = 0.8f),
                        start = Offset(plotLeft, currentY),
                        end = Offset(plotRight, currentY),
                        strokeWidth = 1.75.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                val controlPath = Path().apply {
                    controlPoints.forEachIndexed { index, point ->
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                drawPath(
                    path = controlPath,
                    color = primary,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )

                val sampleRadius = 1.8.dp.toPx()
                runtimePreview.indices.forEachIndexed { step, displayIndex ->
                    val normalizedX = if (runtimePreview.effectivePressCount == 0) {
                        0.0
                    } else {
                        step.toDouble() / runtimePreview.effectivePressCount.toDouble()
                    }
                    drawCircle(
                        color = primary.copy(alpha = 0.68f),
                        radius = sampleRadius,
                        center = pointFor(normalizedX, displayIndex.toDouble()),
                    )
                }

                controlPoints.forEachIndexed { index, point ->
                    val isSelected = index == selectedIndex
                    if (isSelected) {
                        drawCircle(
                            color = primary.copy(alpha = 0.13f),
                            radius = 10.dp.toPx(),
                            center = point,
                        )
                    }
                    drawCircle(
                        color = if (isSelected) primary else surface,
                        radius = if (isSelected) 5.5.dp.toPx() else 4.2.dp.toPx(),
                        center = point,
                    )
                    if (!isSelected) {
                        drawCircle(
                            color = primary,
                            radius = 4.2.dp.toPx(),
                            center = point,
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }
                }

                if (currentY != null && currentX != null && currentIndexLabel != null) {
                    val intersection = Offset(
                        x = plotLeft + currentX.toFloat() * plotWidth,
                        y = currentY,
                    )
                    drawCircle(
                        color = current.copy(alpha = 0.18f),
                        radius = 7.5.dp.toPx(),
                        center = intersection,
                    )
                    drawCircle(
                        color = current,
                        radius = 4.dp.toPx(),
                        center = intersection,
                    )
                    drawCircle(
                        color = surface,
                        radius = 4.dp.toPx(),
                        center = intersection,
                        style = Stroke(width = 2.dp.toPx()),
                    )

                    val tagText = "当前 $currentIndexLabel"
                    val tagPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = currentContent.toArgb()
                        textSize = 11.sp.toPx()
                        textAlign = AndroidPaint.Align.CENTER
                        typeface = android.graphics.Typeface.create(
                            "sans-serif-medium",
                            android.graphics.Typeface.NORMAL,
                        )
                    }
                    val fontMetrics = tagPaint.fontMetrics
                    val tagHorizontalPadding = 8.dp.toPx()
                    val tagVerticalPadding = 3.dp.toPx()
                    val tagWidth = maxOf(
                        48.dp.toPx(),
                        tagPaint.measureText(tagText) + 2f * tagHorizontalPadding,
                    ).coerceAtMost(plotWidth)
                    val textHeight = fontMetrics.descent - fontMetrics.ascent
                    val tagHeight = maxOf(
                        20.dp.toPx(),
                        textHeight + 2f * tagVerticalPadding,
                    ).coerceAtMost(plotHeight)
                    val tagLeft = (plotRight - tagWidth).coerceAtLeast(plotLeft)
                    val tagTop = (currentY - tagHeight / 2f).coerceIn(
                        plotTop,
                        plotBottom - tagHeight,
                    )
                    drawRoundRect(
                        color = current,
                        topLeft = Offset(tagLeft, tagTop),
                        size = Size(tagWidth, tagHeight),
                        cornerRadius = CornerRadius(7.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        tagText,
                        tagLeft + tagWidth / 2f,
                        tagTop + (tagHeight - textHeight) / 2f - fontMetrics.ascent,
                        tagPaint,
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = tick,
                modifier = Modifier
                    .padding(start = 2.dp, top = 1.dp)
                    .size(16.dp),
            )
        }

        CompactCurveStepper(
            label = "按键 K",
            value = renderedMap.pressCount,
            minimum = 1,
            maximum = renderedMap.basisSpan,
            enabled = editorReady,
            onValueChange = { requested ->
                publish(
                    renderedMap.withPressCount(requested),
                    renderedMap.normalizedXAt(selectedIndex),
                )
            },
            valueTag = CurveEditorTestTags.PRESS_COUNT_INPUT,
            decrementTag = CurveEditorTestTags.PRESS_COUNT_DECREMENT,
            incrementTag = CurveEditorTestTags.PRESS_COUNT_INCREMENT,
        )

        if (snapshot != null && snapshot.range.minIndex == snapshot.range.maxIndex) {
            Text(
                text = "当前输出设备不提供可调音量档位",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CompactCurveStepper(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    valueTag: String,
    decrementTag: String,
    incrementTag: String,
) {
    var draftValue by remember { mutableStateOf(value.toString()) }
    var inputHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val darkTheme = isSystemInDarkTheme()
    val containerColor = if (darkTheme) Color(0xFF28262C) else Color(0xFFF0EDF4)
    val contentColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(value, inputHasFocus) {
        if (!inputHasFocus) draftValue = value.toString()
    }

    fun commitDraft() {
        val requested = draftValue.toIntOrNull()
        if (requested == null) {
            draftValue = value.toString()
            return
        }
        val accepted = requested.coerceIn(minimum, maximum)
        draftValue = accepted.toString()
        if (accepted != value) onValueChange(accepted)
    }

    fun updateFromButton(delta: Int) {
        val base = draftValue.toIntOrNull()?.coerceIn(minimum, maximum) ?: value
        val accepted = (base + delta).coerceIn(minimum, maximum)
        draftValue = accepted.toString()
        if (accepted != value) onValueChange(accepted)
        inputHasFocus = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val buttonBase = draftValue.toIntOrNull()?.coerceIn(minimum, maximum) ?: value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(
            onClick = { updateFromButton(-1) },
            enabled = enabled && buttonBase > minimum,
            modifier = Modifier
                .size(40.dp)
                .testTag(decrementTag),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "$label 减少",
                modifier = Modifier.size(18.dp),
            )
        }
        BasicTextField(
            value = draftValue,
            onValueChange = { requested ->
                if (requested.all(Char::isDigit) && requested.length <= 9) {
                    draftValue = requested
                }
            },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .width(52.dp)
                .height(48.dp)
                .testTag(valueTag)
                .onFocusChanged { focusState ->
                    if (inputHasFocus && !focusState.isFocused) commitDraft()
                    inputHasFocus = focusState.isFocused
                }
                .semantics {
                    contentDescription = label
                    stateDescription = draftValue.ifBlank { "空" }
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commitDraft()
                    inputHasFocus = false
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                },
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    innerTextField()
                }
            },
        )
        IconButton(
            onClick = { updateFromButton(1) },
            enabled = enabled && buttonBase < maximum,
            modifier = Modifier
                .size(40.dp)
                .testTag(incrementTag),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "$label 增加",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val MAXIMUM_CONTROL_POINT_COUNT = 16

private fun StepVolumeMap.normalizedXForDisplayedIndex(
    displayedIndices: List<Int>,
    requestedIndex: Double,
): Double {
    require(displayedIndices.size == controlPointCount)
    if (!requestedIndex.isFinite() || requestedIndex <= displayedIndices.first()) return 0.0
    if (requestedIndex >= displayedIndices.last()) return 1.0
    val rightIndex = displayedIndices.indexOfFirst { it.toDouble() >= requestedIndex }
        .coerceIn(1, controlSegmentCount)
    val leftIndex = rightIndex - 1
    val leftIndexValue = displayedIndices[leftIndex].toDouble()
    val rightIndexValue = displayedIndices[rightIndex].toDouble()
    if (rightIndexValue == leftIndexValue) {
        return (normalizedXs[leftIndex] + normalizedXs[rightIndex]) / 2.0
    }
    val fraction = (requestedIndex - leftIndexValue) / (rightIndexValue - leftIndexValue)
    return normalizedXs[leftIndex] +
        (normalizedXs[rightIndex] - normalizedXs[leftIndex]) * fraction
}

private fun Double.formatAxisPosition(): String {
    val doubled = (this * 2.0).roundToInt()
    return if (doubled % 2 == 0) (doubled / 2).toString() else "${doubled / 2}.5"
}

object CurveEditorTestTags {
    const val CANVAS = "mapping_curve_canvas"
    const val CURRENT_VOLUME_MARKER = "mapping_curve_current_volume"
    const val PRESS_COUNT_INPUT = "mapping_curve_press_count_input"
    const val PRESS_COUNT_DECREMENT = "mapping_curve_press_count_decrement"
    const val PRESS_COUNT_INCREMENT = "mapping_curve_press_count_increment"
    const val CONTROL_POINT_COUNT_INPUT = "mapping_curve_control_point_count_input"
    const val CONTROL_POINT_COUNT_DECREMENT = "mapping_curve_control_point_count_decrement"
    const val CONTROL_POINT_COUNT_INCREMENT = "mapping_curve_control_point_count_increment"

    // Source-compatible aliases for older test clients. These controls are intentionally absent.
    const val PRESS_COUNT_APPLY = "mapping_curve_press_count_apply"
    const val PRESS_COUNT_PRESETS = "mapping_curve_press_count_presets"
    const val CONTROL_POINT_COUNT_APPLY = "mapping_curve_control_point_count_apply"
    const val CONTROL_POINT_SELECTOR = "mapping_curve_control_point_selector"
    const val SELECTED_CONTROL_POINT_VALUE = "mapping_curve_selected_control_point_value"
    const val INDEX_SLIDER = "mapping_curve_index_slider"
    const val INDEX_INPUT = "mapping_curve_index_input"
    const val INDEX_DECREMENT = "mapping_curve_index_decrement"
    const val INDEX_INCREMENT = "mapping_curve_index_increment"
    const val APPLY = "mapping_curve_apply"
    const val UNDO = "mapping_curve_undo"
    const val REDO = "mapping_curve_redo"
    const val RESET_LINEAR = "mapping_curve_reset_linear"
    const val DETAILS_TOGGLE = "mapping_curve_details_toggle"
    const val STEP_SELECTOR = CONTROL_POINT_SELECTOR
    const val SELECTED_STEP_VALUE = SELECTED_CONTROL_POINT_VALUE
    const val POINT_SELECTOR = CONTROL_POINT_SELECTOR
    const val SELECTED_POINT_VALUE = SELECTED_CONTROL_POINT_VALUE
    const val X_SLIDER = PRESS_COUNT_INPUT
    const val Y_SLIDER = INDEX_SLIDER

    fun controlPoint(index: Int): String = "mapping_curve_control_point_$index"
}
