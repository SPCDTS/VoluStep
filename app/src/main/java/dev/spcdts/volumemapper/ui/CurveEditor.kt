package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.StepVolumeMap
import kotlin.math.roundToInt

/**
 * Edits a control-point polyline and its independent runtime sampling count. P control points are
 * uniformly spaced across the same x axis on which K short presses produce K + 1 sampled states.
 * Only completed gestures/actions are published to the repository.
 */
@Composable
fun MappingCurveEditor(
    outputMap: StepVolumeMap,
    snapshot: RouteVolumeSnapshot?,
    currentLogicalPosition: Double?,
    onMapCommitted: (StepVolumeMap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val routeMinimum = snapshot?.range?.minIndex
    val routeMaximum = snapshot?.range?.maxIndex
    val incomingMap = outputMap
    val incomingSource = remember(incomingMap, routeMinimum, routeMaximum) {
        EditorSource(incomingMap, routeMinimum, routeMaximum)
    }
    val latestOnMapCommitted by rememberUpdatedState(onMapCommitted)

    var editorMap by remember { mutableStateOf(incomingMap) }
    var selectedControlPoint by remember {
        mutableIntStateOf(1.coerceAtMost(incomingMap.controlSegmentCount))
    }
    var undoStack by remember { mutableStateOf(emptyList<EditorHistoryEntry>()) }
    var redoStack by remember { mutableStateOf(emptyList<EditorHistoryEntry>()) }
    var pressCountText by remember { mutableStateOf(incomingMap.pressCount.toString()) }
    var controlPointCountText by remember {
        mutableStateOf(incomingMap.controlPointCount.toString())
    }
    var indexText by remember {
        mutableStateOf(
            (
                incomingMap.displayIndexBase(routeMinimum, routeMaximum) +
                    incomingMap.offsets[selectedControlPoint]
                ).toString(),
        )
    }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var canvasGestureInProgress by remember { mutableStateOf(false) }
    var pendingExternalSource by remember { mutableStateOf<EditorSource?>(null) }
    var lastExternalSource by remember { mutableStateOf(incomingSource) }

    val synchronizeFromExternal: (StepVolumeMap) -> Unit = { synchronizedMap ->
        val oldSegmentCount = editorMap.controlSegmentCount.coerceAtLeast(1)
        val logicalSelection = selectedControlPoint.toDouble() / oldSegmentCount.toDouble()
        val synchronizedSelection =
            (logicalSelection * synchronizedMap.controlSegmentCount).roundToInt()
                .coerceIn(0, synchronizedMap.controlSegmentCount)
        editorMap = synchronizedMap
        selectedControlPoint = synchronizedSelection
        pressCountText = synchronizedMap.pressCount.toString()
        controlPointCountText = synchronizedMap.controlPointCount.toString()
        indexText = (
            synchronizedMap.displayIndexBase(routeMinimum, routeMaximum) +
                synchronizedMap.offsets[synchronizedSelection]
            ).toString()
        undoStack = emptyList()
        redoStack = emptyList()
    }

    val publishCompletedAction: (StepVolumeMap, StepVolumeMap, Int, Int) -> Unit =
        { before, after, previousSelection, requestedSelection ->
            val nextSelection = requestedSelection.coerceIn(0, after.controlSegmentCount)
            editorMap = after
            selectedControlPoint = nextSelection
            pressCountText = after.pressCount.toString()
            controlPointCountText = after.controlPointCount.toString()
            indexText = (
                after.displayIndexBase(routeMinimum, routeMaximum) + after.offsets[nextSelection]
                ).toString()
            if (after != before) {
                undoStack = undoStack.appendHistory(
                    EditorHistoryEntry(
                        map = before,
                        selectedControlPoint = previousSelection.coerceIn(
                            0,
                            before.controlSegmentCount,
                        ),
                    ),
                )
                redoStack = emptyList()
                latestOnMapCommitted(after)
            }
        }

    LaunchedEffect(incomingSource) {
        val previousSource = lastExternalSource
        if (incomingSource == previousSource) return@LaunchedEffect
        lastExternalSource = incomingSource
        val routeFrameChanged =
            previousSource.routeMinimum != incomingSource.routeMinimum ||
                previousSource.routeMaximum != incomingSource.routeMaximum
        if (canvasGestureInProgress) {
            pendingExternalSource = incomingSource
        } else if (routeFrameChanged || incomingMap != editorMap) {
            synchronizeFromExternal(incomingMap)
        }
    }

    val routeSpan = snapshot?.range?.let { it.maxIndex - it.minIndex }
    val routeMatchesBasis = routeSpan == null || routeSpan == editorMap.basisSpan
    val displayUsesReferenceBasis =
        snapshot != null && routeSpan != null && routeSpan > 0 && !routeMatchesBasis
    val displayMinimum = if (displayUsesReferenceBasis) 0 else routeMinimum.orZero()
    val displayMaximum = if (displayUsesReferenceBasis) {
        editorMap.basisSpan
    } else {
        routeMaximum ?: editorMap.basisSpan
    }
    val editorReady = when {
        snapshot == null -> true
        routeSpan == null || routeSpan <= 0 -> false
        else -> true
    }
    val maximumPressCount = editorMap.basisSpan
    val maximumControlPointCount = editorMap.basisSpan + 1
    val actualControlIndices = editorMap.displayControlIndices(snapshot)
    val actualPressIndices = editorMap.actualPressIndices(snapshot)
    val displayedPressIndices = actualPressIndices.map { actualIndex ->
        if (!displayUsesReferenceBasis) {
            actualIndex.toDouble()
        } else {
            (actualIndex - routeMinimum.orZero()).toDouble() * editorMap.basisSpan.toDouble() /
                routeSpan.toDouble()
        }
    }
    val visiblePressCount = actualPressIndices.lastIndex
    val safeSelectedControlPoint = selectedControlPoint.coerceIn(
        0,
        editorMap.controlSegmentCount,
    )
    val selectedActualIndex = actualControlIndices[safeSelectedControlPoint]
    val selectedIsEndpoint =
        safeSelectedControlPoint == 0 ||
            safeSelectedControlPoint == editorMap.controlSegmentCount
    val selectedMinimumIndex = if (selectedIsEndpoint || !editorReady) {
        selectedActualIndex
    } else {
        displayMinimum + editorMap.offsets[safeSelectedControlPoint - 1] + 1
    }
    val selectedMaximumIndex = if (selectedIsEndpoint || !editorReady) {
        selectedActualIndex
    } else {
        displayMinimum + editorMap.offsets[safeSelectedControlPoint + 1] - 1
    }
    val selectedCanMove =
        editorReady && !selectedIsEndpoint && selectedMinimumIndex < selectedMaximumIndex

    LaunchedEffect(editorMap.controlSegmentCount) {
        if (selectedControlPoint > editorMap.controlSegmentCount) {
            selectedControlPoint = editorMap.controlSegmentCount
        }
    }
    LaunchedEffect(editorMap.pressCount) {
        pressCountText = editorMap.pressCount.toString()
    }
    LaunchedEffect(editorMap.controlPointCount) {
        controlPointCountText = editorMap.controlPointCount.toString()
    }
    LaunchedEffect(safeSelectedControlPoint, actualControlIndices, displayMinimum) {
        indexText = selectedActualIndex.toString()
    }

    val applyPressCount: () -> Unit = {
        val requested = pressCountText.toIntOrNull()
        if (editorReady && requested != null && requested in 1..maximumPressCount) {
            val before = editorMap
            val after = before.withPressCount(requested)
            publishCompletedAction(
                before,
                after,
                safeSelectedControlPoint,
                safeSelectedControlPoint,
            )
        }
    }
    val applyControlPointCount: () -> Unit = {
        val requested = controlPointCountText.toIntOrNull()
        if (
            editorReady &&
            requested != null &&
            requested in 2..maximumControlPointCount
        ) {
            val before = editorMap
            val logicalSelection =
                selectedControlPoint.toDouble() / before.controlSegmentCount.toDouble()
            val after = before.resampleControlPoints(requested)
            val nextSelection =
                (logicalSelection * after.controlSegmentCount).roundToInt()
            publishCompletedAction(
                before,
                after,
                safeSelectedControlPoint,
                nextSelection,
            )
        }
    }
    val applyIndexText: () -> Unit = {
        val requestedIndex = indexText.toIntOrNull()
        if (
            editorReady &&
            !selectedIsEndpoint &&
            requestedIndex != null &&
            requestedIndex in selectedMinimumIndex..selectedMaximumIndex
        ) {
            val before = editorMap
            val after = before.withOffset(
                safeSelectedControlPoint,
                requestedIndex - displayMinimum,
            )
            publishCompletedAction(
                before,
                after,
                safeSelectedControlPoint,
                safeSelectedControlPoint,
            )
        }
    }

    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { 24.dp.toPx() }
    val topPaddingPx = with(density) { 18.dp.toPx() }
    val chartToDeltaGapPx = with(density) { 16.dp.toPx() }
    val deltaBandHeightPx = with(density) { 38.dp.toPx() }
    val bottomPaddingPx = with(density) { 8.dp.toPx() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.onSurface
    val deltaColor = MaterialTheme.colorScheme.secondary
    val pointFillColor = MaterialTheme.colorScheme.surface
    val maximumActualPressDelta = actualPressIndices.zipWithNext { left, right -> right - left }
        .maxOrNull()

    val canvasStateDescription = buildString {
        append("配置从最小到最大需要 ")
        append(editorMap.pressCount)
        append(" 次短按；当前路由有 ")
        append(visiblePressCount)
        append(" 次有效短按，共 ")
        append(visiblePressCount + 1)
        append(" 个状态；折线由 ")
        append(editorMap.controlPointCount)
        append(" 个控制点搭建，当前选择第 ")
        append(safeSelectedControlPoint + 1)
        append(" 个控制点，")
        append(if (displayUsesReferenceBasis) "配置基准 index 为 " else "Audio volume index 为 ")
        append(selectedActualIndex)
        maximumActualPressDelta?.let { delta ->
            append("；当前路由最大单次 index 跨度为 ")
            append(delta)
        }
    }
    val requestedPressCount = pressCountText.toIntOrNull()
    val pressCountIsValid =
        requestedPressCount != null && requestedPressCount in 1..maximumPressCount
    val showPressCountApply = pressCountIsValid && requestedPressCount != editorMap.pressCount
    val requestedControlPointCount = controlPointCountText.toIntOrNull()
    val controlPointCountIsValid =
        requestedControlPointCount != null &&
            requestedControlPointCount in 2..maximumControlPointCount
    val showControlPointCountApply =
        controlPointCountIsValid && requestedControlPointCount != editorMap.controlPointCount

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "按键次数",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(
                onClick = {
                    pressCountText = (editorMap.pressCount - 1).toString()
                    applyPressCount()
                },
                enabled = editorReady && editorMap.pressCount > 1,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.PRESS_COUNT_DECREMENT)
                    .semantics { contentDescription = "按键次数减少 1" },
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = pressCountText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) pressCountText = value
                },
                enabled = editorReady,
                singleLine = true,
                isError = !pressCountIsValid,
                label = { Text("K") },
                trailingIcon = if (showPressCountApply) {
                    {
                        IconButton(
                            onClick = applyPressCount,
                            modifier = Modifier
                                .testTag(CurveEditorTestTags.PRESS_COUNT_APPLY)
                                .semantics { contentDescription = "应用按键次数" },
                        ) {
                            Text("✓")
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { applyPressCount() }),
                modifier = Modifier
                    .width(104.dp)
                    .testTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
                    .semantics {
                        contentDescription = "按键次数 K，可用范围 1 到 $maximumPressCount"
                        if (!pressCountIsValid) {
                            error("请输入 1 到 $maximumPressCount 之间的整数")
                        }
                    },
            )
            IconButton(
                onClick = {
                    pressCountText = (editorMap.pressCount + 1).toString()
                    applyPressCount()
                },
                enabled = editorReady && editorMap.pressCount < maximumPressCount,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.PRESS_COUNT_INCREMENT)
                    .semantics { contentDescription = "按键次数增加 1" },
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }

        if (editorReady && !pressCountIsValid) {
            Text(
                text = "请输入 1…$maximumPressCount 之间的整数",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "控制点（含两端）",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(
                onClick = {
                    controlPointCountText = (editorMap.controlPointCount - 1).toString()
                    applyControlPointCount()
                },
                enabled = editorReady && editorMap.controlPointCount > 2,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT)
                    .semantics { contentDescription = "控制点数量减少 1" },
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = controlPointCountText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) {
                        controlPointCountText = value
                    }
                },
                enabled = editorReady,
                singleLine = true,
                isError = !controlPointCountIsValid,
                label = { Text("P") },
                trailingIcon = if (showControlPointCountApply) {
                    {
                        IconButton(
                            onClick = applyControlPointCount,
                            modifier = Modifier
                                .testTag(CurveEditorTestTags.CONTROL_POINT_COUNT_APPLY)
                                .semantics { contentDescription = "应用控制点数量" },
                        ) {
                            Text("✓")
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { applyControlPointCount() }),
                modifier = Modifier
                    .width(104.dp)
                    .testTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
                    .semantics {
                        contentDescription =
                            "控制点数量 P，包含两个端点，可用范围 2 到 $maximumControlPointCount"
                        if (!controlPointCountIsValid) {
                            error("请输入 2 到 $maximumControlPointCount 之间的整数")
                        }
                    },
            )
            IconButton(
                onClick = {
                    controlPointCountText = (editorMap.controlPointCount + 1).toString()
                    applyControlPointCount()
                },
                enabled =
                    editorReady && editorMap.controlPointCount < maximumControlPointCount,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT)
                    .semantics { contentDescription = "控制点数量增加 1" },
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }

        if (editorReady && !controlPointCountIsValid) {
            Text(
                text = "请输入 2…$maximumControlPointCount 之间的整数",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (editorReady && editorMap.pressCount == editorMap.basisSpan) {
            Text(
                text = "每次固定 +1；减小 K 后可调整不同区段的步幅。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text = if (displayUsesReferenceBasis) {
                "配置 K=${editorMap.pressCount}  ·  ${editorMap.controlPointCount} 个控制点  ·  基准 index 0…${editorMap.basisSpan}"
            } else {
                "按键 0…${editorMap.pressCount}  ·  ${editorMap.controlPointCount} 个均匀控制点  ·  Audio index $displayMinimum…$displayMaximum"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )

        if (displayUsesReferenceBasis) {
            Text(
                text =
                    "小点按当前路由 index ${routeMinimum.orZero()}…${routeMaximum.orZero()} 归一化显示；完整 K/P 配置不会被临时路由覆盖。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (editorReady && editorMap.controlPointCount > editorMap.pressCount + 1) {
            Text(
                text = "控制点多于按键状态；额外细节会在整数按键位置采样。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag(CurveEditorTestTags.CANVAS)
                .semantics {
                    contentDescription = if (displayUsesReferenceBasis) {
                        "音量映射曲线。横轴是配置按键进度，纵轴是可移植配置基准 index；大点是可编辑控制点，小点是当前路由实际按键状态的归一化位置。TalkBack 用户可使用下方精确编辑"
                    } else {
                        "音量映射曲线。横轴是按键进度，纵轴是整数 Audio volume index；大点是可编辑控制点，小点是实际按键状态。TalkBack 用户可使用下方精确编辑"
                    }
                    stateDescription = canvasStateDescription
                }
                .pointerInput(
                    editorReady,
                    displayMinimum,
                    displayMaximum,
                    editorMap.controlPointCount,
                ) {
                    if (!editorReady) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val left = horizontalPaddingPx
                        val right = size.width - horizontalPaddingPx
                        val deltaBottom = size.height - bottomPaddingPx
                        val deltaTop = deltaBottom - deltaBandHeightPx
                        val plotTop = topPaddingPx
                        val plotBottom = deltaTop - chartToDeltaGapPx
                        if (
                            down.position.y < plotTop - topPaddingPx ||
                            down.position.y > plotBottom + chartToDeltaGapPx / 2f
                        ) {
                            return@awaitEachGesture
                        }

                        val plotWidth = (right - left).coerceAtLeast(1f)
                        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
                        fun controlPointAt(position: Offset): Int =
                            ((((position.x - left) / plotWidth).coerceIn(0f, 1f)) *
                                editorMap.controlSegmentCount.toFloat())
                                .roundToInt()
                                .coerceIn(0, editorMap.controlSegmentCount)

                        fun offsetAt(y: Float): Int =
                            ((((plotBottom - y) / plotHeight).coerceIn(0f, 1f)) *
                                editorMap.basisSpan.toFloat())
                                .roundToInt()
                                .coerceIn(0, editorMap.basisSpan)

                        fun paintSegment(from: Offset?, to: Offset) {
                            val toControlPoint = controlPointAt(to)
                            var paintedMap = editorMap
                            if (from == null) {
                                paintedMap = paintedMap.withOffsetPushing(
                                    toControlPoint,
                                    offsetAt(to.y),
                                )
                            } else {
                                val fromControlPoint = controlPointAt(from)
                                if (fromControlPoint == toControlPoint) {
                                    paintedMap = paintedMap.withOffsetPushing(
                                        toControlPoint,
                                        offsetAt(to.y),
                                    )
                                } else {
                                    val traversedControlPoints =
                                        if (fromControlPoint < toControlPoint) {
                                            fromControlPoint..toControlPoint
                                        } else {
                                            fromControlPoint downTo toControlPoint
                                        }
                                    traversedControlPoints.forEach { controlPoint ->
                                        val fraction =
                                            (controlPoint - fromControlPoint).toFloat() /
                                                (toControlPoint - fromControlPoint).toFloat()
                                        val interpolatedY = from.y + (to.y - from.y) * fraction
                                        paintedMap = paintedMap.withOffsetPushing(
                                            controlPoint,
                                            offsetAt(interpolatedY),
                                        )
                                    }
                                }
                            }
                            editorMap = paintedMap
                            selectedControlPoint = toControlPoint
                            indexText = (
                                displayMinimum + paintedMap.offsets[toControlPoint]
                                ).toString()
                        }

                        val beforeGesture = editorMap
                        val beforeGestureSelection = selectedControlPoint
                        var previousPosition: Offset? = null
                        var gestureCompleted = false
                        canvasGestureInProgress = true
                        down.consume()
                        try {
                            paintSegment(null, down.position)
                            previousPosition = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    gestureCompleted = true
                                    break
                                }
                                paintSegment(previousPosition, change.position)
                                previousPosition = change.position
                                change.consume()
                            }
                        } finally {
                            canvasGestureInProgress = false
                            val pendingSource = pendingExternalSource
                            pendingExternalSource = null
                            when {
                                pendingSource != null -> synchronizeFromExternal(pendingSource.map)
                                gestureCompleted -> publishCompletedAction(
                                    beforeGesture,
                                    editorMap,
                                    beforeGestureSelection,
                                    selectedControlPoint,
                                )
                                else -> {
                                    editorMap = beforeGesture
                                    selectedControlPoint = beforeGestureSelection
                                    pressCountText = beforeGesture.pressCount.toString()
                                    controlPointCountText =
                                        beforeGesture.controlPointCount.toString()
                                    indexText = (
                                        beforeGesture.displayIndexBase(
                                            routeMinimum,
                                            routeMaximum,
                                        ) + beforeGesture.offsets[beforeGestureSelection]
                                        ).toString()
                                }
                            }
                        }
                    }
                },
        ) {
            val left = horizontalPaddingPx
            val right = size.width - horizontalPaddingPx
            val deltaBottom = size.height - bottomPaddingPx
            val deltaTop = deltaBottom - deltaBandHeightPx
            val plotTop = topPaddingPx
            val plotBottom = deltaTop - chartToDeltaGapPx
            val plotWidth = (right - left).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val indexSpan = (displayMaximum - displayMinimum).coerceAtLeast(0)

            fun pointFor(normalizedX: Float, index: Double): Offset {
                val yFraction = if (indexSpan == 0) {
                    0.5f
                } else {
                    ((index - displayMinimum.toDouble()) / indexSpan.toDouble()).toFloat()
                }
                return Offset(
                    x = left + normalizedX * plotWidth,
                    y = plotBottom - yFraction * plotHeight,
                )
            }

            repeat(5) { gridIndex ->
                val fraction = gridIndex / 4f
                val alpha = if (gridIndex == 0 || gridIndex == 4) 0.22f else 0.09f
                drawLine(
                    color = gridColor.copy(alpha = alpha),
                    start = Offset(left, plotTop + fraction * plotHeight),
                    end = Offset(right, plotTop + fraction * plotHeight),
                )
                drawLine(
                    color = gridColor.copy(alpha = alpha),
                    start = Offset(left + fraction * plotWidth, plotTop),
                    end = Offset(left + fraction * plotWidth, plotBottom),
                )
            }

            val controlPath = Path()
            actualControlIndices.forEachIndexed { controlPoint, index ->
                val normalizedX =
                    controlPoint.toFloat() / editorMap.controlSegmentCount.toFloat()
                val point = pointFor(normalizedX, index.toDouble())
                if (controlPoint == 0) controlPath.moveTo(point.x, point.y)
                else controlPath.lineTo(point.x, point.y)
            }
            drawPath(
                path = controlPath,
                color = primaryColor,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )

            currentLogicalPosition?.takeIf { it.isFinite() }?.let { logicalPosition ->
                val lineX = left + logicalPosition.coerceIn(0.0, 1.0).toFloat() * plotWidth
                drawLine(
                    color = selectedColor,
                    start = Offset(lineX, plotTop),
                    end = Offset(lineX, plotBottom),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                )
            }

            val pressSpacing = plotWidth / visiblePressCount.coerceAtLeast(1).toFloat()
            displayedPressIndices.forEachIndexed { step, displayIndex ->
                val showState =
                    pressSpacing >= 8.dp.toPx() || step == 0 || step == visiblePressCount
                if (!showState) return@forEachIndexed
                val normalizedX = step.toFloat() / visiblePressCount.coerceAtLeast(1).toFloat()
                drawCircle(
                    color = deltaColor.copy(alpha = 0.62f),
                    radius = 2.5.dp.toPx(),
                    center = pointFor(normalizedX, displayIndex),
                )
            }

            val controlSpacing =
                plotWidth / editorMap.controlSegmentCount.coerceAtLeast(1).toFloat()
            val controlRadius = (controlSpacing / 4f).coerceIn(2.dp.toPx(), 7.dp.toPx())
            actualControlIndices.forEachIndexed { controlPoint, index ->
                val selected = controlPoint == safeSelectedControlPoint
                val showControlPoint =
                    controlSpacing >= 8.dp.toPx() ||
                        controlPoint == 0 ||
                        controlPoint == editorMap.controlSegmentCount ||
                        selected
                if (!showControlPoint) return@forEachIndexed
                val normalizedX =
                    controlPoint.toFloat() / editorMap.controlSegmentCount.toFloat()
                val point = pointFor(normalizedX, index.toDouble())
                drawCircle(
                    color = if (selected) selectedColor else primaryColor,
                    radius = if (selected) 9.dp.toPx() else controlRadius,
                    center = point,
                )
                drawCircle(
                    color = pointFillColor,
                    radius = if (selected) 4.dp.toPx() else controlRadius / 2f,
                    center = point,
                )
            }

            val deltas = actualPressIndices.zipWithNext { leftIndex, rightIndex ->
                rightIndex - leftIndex
            }
            val largestDelta = deltas.maxOrNull()?.coerceAtLeast(1) ?: 1
            deltas.forEachIndexed { step, delta ->
                val segmentLeft = left + step.toFloat() / visiblePressCount * plotWidth
                val segmentRight = left + (step + 1).toFloat() / visiblePressCount * plotWidth
                val barHeight = deltaBandHeightPx * delta.toFloat() / largestDelta.toFloat()
                drawRect(
                    color = deltaColor.copy(
                        alpha = 0.28f + 0.62f * delta.toFloat() / largestDelta.toFloat(),
                    ),
                    topLeft = Offset(segmentLeft + 1f, deltaBottom - barHeight),
                    size = Size(
                        width = (segmentRight - segmentLeft - 2f).coerceAtLeast(1f),
                        height = barHeight.coerceAtLeast(1f),
                    ),
                )
            }
            drawLine(
                color = gridColor.copy(alpha = 0.22f),
                start = Offset(left, deltaBottom),
                end = Offset(right, deltaBottom),
            )
        }

        val selectedPressPosition =
            safeSelectedControlPoint.toDouble() * editorMap.pressCount.toDouble() /
                editorMap.controlSegmentCount.toDouble()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(
                        "控制点 ${safeSelectedControlPoint + 1} / ${editorMap.controlPointCount}",
                    )
                    append(
                        "  ·  x ${selectedPressPosition.formatAxisPosition()} / ${editorMap.pressCount}",
                    )
                    append("  ·  index $selectedActualIndex")
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.SELECTED_CONTROL_POINT_VALUE),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(
                onClick = {
                    val current = editorMap
                    val currentSelection = safeSelectedControlPoint
                    val previous = undoStack.last()
                    undoStack = undoStack.dropLast(1)
                    redoStack = redoStack.appendHistory(
                        EditorHistoryEntry(current, currentSelection),
                    )
                    editorMap = previous.map
                    selectedControlPoint = previous.selectedControlPoint
                    pressCountText = previous.map.pressCount.toString()
                    controlPointCountText = previous.map.controlPointCount.toString()
                    indexText = (
                        previous.map.displayIndexBase(routeMinimum, routeMaximum) +
                            previous.map.offsets[previous.selectedControlPoint]
                        ).toString()
                    latestOnMapCommitted(previous.map)
                },
                enabled = editorReady && undoStack.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.UNDO)
                    .semantics { contentDescription = "撤销曲线编辑" },
            ) {
                Text("↶", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(
                onClick = {
                    val current = editorMap
                    val currentSelection = safeSelectedControlPoint
                    val next = redoStack.last()
                    redoStack = redoStack.dropLast(1)
                    undoStack = undoStack.appendHistory(
                        EditorHistoryEntry(current, currentSelection),
                    )
                    editorMap = next.map
                    selectedControlPoint = next.selectedControlPoint
                    pressCountText = next.map.pressCount.toString()
                    controlPointCountText = next.map.controlPointCount.toString()
                    indexText = (
                        next.map.displayIndexBase(routeMinimum, routeMaximum) +
                            next.map.offsets[next.selectedControlPoint]
                        ).toString()
                    latestOnMapCommitted(next.map)
                },
                enabled = editorReady && redoStack.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.REDO)
                    .semantics { contentDescription = "重做曲线编辑" },
            ) {
                Text("↷", style = MaterialTheme.typography.titleLarge)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedIsEndpoint) "端点固定" else "微调 index",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = {
                    val before = editorMap
                    val after = before.withOffset(
                        safeSelectedControlPoint,
                        selectedActualIndex - 1 - displayMinimum,
                    )
                    publishCompletedAction(
                        before,
                        after,
                        safeSelectedControlPoint,
                        safeSelectedControlPoint,
                    )
                },
                enabled = editorReady &&
                    !selectedIsEndpoint &&
                    selectedActualIndex > selectedMinimumIndex,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.INDEX_DECREMENT)
                    .semantics { contentDescription = "Audio volume index 减少 1" },
            ) {
                Text("−1", style = MaterialTheme.typography.titleMedium)
            }
            IconButton(
                onClick = {
                    val before = editorMap
                    val after = before.withOffset(
                        safeSelectedControlPoint,
                        selectedActualIndex + 1 - displayMinimum,
                    )
                    publishCompletedAction(
                        before,
                        after,
                        safeSelectedControlPoint,
                        safeSelectedControlPoint,
                    )
                },
                enabled = editorReady &&
                    !selectedIsEndpoint &&
                    selectedActualIndex < selectedMaximumIndex,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CurveEditorTestTags.INDEX_INCREMENT)
                    .semantics { contentDescription = "Audio volume index 增加 1" },
            ) {
                Text("+1", style = MaterialTheme.typography.titleMedium)
            }
        }

        OutlinedButton(
            onClick = { detailsExpanded = !detailsExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(CurveEditorTestTags.DETAILS_TOGGLE)
                .semantics {
                    contentDescription = "精确编辑"
                    stateDescription = if (detailsExpanded) "已展开" else "已折叠"
                },
        ) {
            Text(if (detailsExpanded) "收起精确编辑 ︿" else "精确编辑 ﹀")
        }

        if (detailsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        "选择控制点：${safeSelectedControlPoint + 1} / ${editorMap.controlPointCount}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = safeSelectedControlPoint.toFloat(),
                    onValueChange = { value ->
                        val nextControlPoint = value.roundToInt().coerceIn(
                            0,
                            editorMap.controlSegmentCount,
                        )
                        selectedControlPoint = nextControlPoint
                        indexText = actualControlIndices[nextControlPoint].toString()
                    },
                    enabled = editorReady,
                    valueRange = 0f..editorMap.controlSegmentCount.coerceAtLeast(1).toFloat(),
                    steps = (editorMap.controlPointCount - 2).coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(CurveEditorTestTags.CONTROL_POINT_SELECTOR)
                        .semantics {
                            contentDescription = "选择曲线控制点"
                            stateDescription =
                                "第 ${safeSelectedControlPoint + 1} 个，共 ${editorMap.controlPointCount} 个"
                        },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = indexText,
                        onValueChange = { value ->
                            if (
                                value.isEmpty() ||
                                value == "-" ||
                                value.removePrefix("-").all(Char::isDigit)
                            ) {
                                indexText = value
                            }
                        },
                        enabled = selectedCanMove,
                        singleLine = true,
                        isError = selectedCanMove && indexText.toIntOrNull()?.let {
                            it !in selectedMinimumIndex..selectedMaximumIndex
                        } != false,
                        label = {
                            Text(
                                if (displayUsesReferenceBasis) {
                                    "配置基准 index"
                                } else {
                                    "Audio volume index"
                                },
                            )
                        },
                        supportingText = {
                            Text(
                                if (selectedIsEndpoint) {
                                    "端点固定为${if (safeSelectedControlPoint == 0) "最小" else "最大"}音量"
                                } else if (!selectedCanMove) {
                                    "相邻控制点已占满；可减少 P 或在画布上推挤"
                                } else {
                                    "可用范围 $selectedMinimumIndex…$selectedMaximumIndex"
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { applyIndexText() }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(CurveEditorTestTags.INDEX_INPUT),
                    )
                    Button(
                        onClick = applyIndexText,
                        enabled = editorReady &&
                            !selectedIsEndpoint &&
                            indexText.toIntOrNull()?.let {
                                it in selectedMinimumIndex..selectedMaximumIndex &&
                                    it != selectedActualIndex
                            } == true,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(CurveEditorTestTags.APPLY),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Text("确定")
                    }
                }
            }
        }

        if (snapshot != null && routeSpan == 0) {
            Text(
                text = "当前路由只有一个固定 volume index，无法建立严格递增的按键映射。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class EditorSource(
    val map: StepVolumeMap,
    val routeMinimum: Int?,
    val routeMaximum: Int?,
)

private fun StepVolumeMap.displayIndexBase(routeMinimum: Int?, routeMaximum: Int?): Int {
    val minimum = routeMinimum ?: return 0
    val maximum = routeMaximum ?: return minimum
    val routeSpan = maximum - minimum
    return if (routeSpan == 0 || routeSpan == basisSpan) minimum else 0
}

private fun StepVolumeMap.displayControlIndices(snapshot: RouteVolumeSnapshot?): List<Int> {
    val range = snapshot?.range ?: return offsets
    val routeSpan = range.maxIndex - range.minIndex
    if (routeSpan == 0) return List(controlPointCount) { range.minIndex }
    return if (basisSpan == routeSpan) {
        offsets.map { range.minIndex + it }
    } else {
        offsets
    }
}

private fun StepVolumeMap.actualPressIndices(snapshot: RouteVolumeSnapshot?): List<Int> =
    bind(snapshot?.range ?: RouteVolumeRange(minIndex = 0, maxIndex = basisSpan)).indices

private data class EditorHistoryEntry(
    val map: StepVolumeMap,
    val selectedControlPoint: Int,
)

private fun List<EditorHistoryEntry>.appendHistory(
    entry: EditorHistoryEntry,
): List<EditorHistoryEntry> = (this + entry).takeLast(HISTORY_LIMIT)

private fun Int?.orZero(): Int = this ?: 0

private fun Double.formatAxisPosition(): String {
    val tenths = (this * 10.0).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

private const val HISTORY_LIMIT = 50

object CurveEditorTestTags {
    const val CANVAS = "mapping_curve_canvas"
    const val PRESS_COUNT_INPUT = "mapping_curve_press_count_input"
    const val PRESS_COUNT_DECREMENT = "mapping_curve_press_count_decrement"
    const val PRESS_COUNT_INCREMENT = "mapping_curve_press_count_increment"
    const val PRESS_COUNT_APPLY = "mapping_curve_press_count_apply"
    const val PRESS_COUNT_PRESETS = "mapping_curve_press_count_presets"
    const val CONTROL_POINT_COUNT_INPUT = "mapping_curve_control_point_count_input"
    const val CONTROL_POINT_COUNT_DECREMENT = "mapping_curve_control_point_count_decrement"
    const val CONTROL_POINT_COUNT_INCREMENT = "mapping_curve_control_point_count_increment"
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

    // Kept as source-compatible aliases while existing UI tests migrate.
    const val STEP_SELECTOR = CONTROL_POINT_SELECTOR
    const val SELECTED_STEP_VALUE = SELECTED_CONTROL_POINT_VALUE
    const val POINT_SELECTOR = CONTROL_POINT_SELECTOR
    const val SELECTED_POINT_VALUE = SELECTED_CONTROL_POINT_VALUE
    const val X_SLIDER = PRESS_COUNT_INPUT
    const val Y_SLIDER = INDEX_SLIDER

    fun controlPoint(index: Int): String = "mapping_curve_control_point_$index"
}
