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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.StepVolumeMap
import kotlin.math.roundToInt

/**
 * Edits an integer step map. X positions are deliberately implicit and uniformly spaced: K short
 * presses produce K + 1 states. Only completed gestures/actions are published to the repository.
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
    val incomingMap = remember(outputMap, routeMinimum, routeMaximum) {
        outputMap.forEditing(snapshot)
    }
    val incomingSource = remember(incomingMap, routeMinimum, routeMaximum) {
        EditorSource(incomingMap, routeMinimum, routeMaximum)
    }
    val latestOnMapCommitted by rememberUpdatedState(onMapCommitted)

    var editorMap by remember { mutableStateOf(incomingMap) }
    var selectedStep by remember {
        mutableIntStateOf(1.coerceAtMost(incomingMap.pressCount))
    }
    var undoStack by remember { mutableStateOf(emptyList<StepVolumeMap>()) }
    var redoStack by remember { mutableStateOf(emptyList<StepVolumeMap>()) }
    var pressCountText by remember { mutableStateOf(incomingMap.pressCount.toString()) }
    var indexText by remember {
        mutableStateOf(
            (routeMinimum.orZero() + incomingMap.offsets[selectedStep]).toString(),
        )
    }
    var sliderActionStart by remember { mutableStateOf<StepVolumeMap?>(null) }
    var canvasGestureInProgress by remember { mutableStateOf(false) }
    var pendingExternalSource by remember { mutableStateOf<EditorSource?>(null) }
    var lastExternalSource by remember { mutableStateOf(incomingSource) }

    val synchronizeFromExternal: (StepVolumeMap) -> Unit = { synchronizedMap ->
        val oldPressCount = editorMap.pressCount.coerceAtLeast(1)
        val logicalSelection = selectedStep.toDouble() / oldPressCount.toDouble()
        val synchronizedSelection =
            (logicalSelection * synchronizedMap.pressCount).roundToInt()
                .coerceIn(0, synchronizedMap.pressCount)
        editorMap = synchronizedMap
        selectedStep = synchronizedSelection
        pressCountText = synchronizedMap.pressCount.toString()
        indexText = (routeMinimum.orZero() + synchronizedMap.offsets[synchronizedSelection]).toString()
        undoStack = emptyList()
        redoStack = emptyList()
        sliderActionStart = null
    }

    val publishCompletedAction: (StepVolumeMap, StepVolumeMap, Int) -> Unit =
        { before, after, requestedSelection ->
            val nextSelection = requestedSelection.coerceIn(0, after.pressCount)
            editorMap = after
            selectedStep = nextSelection
            pressCountText = after.pressCount.toString()
            indexText = (routeMinimum.orZero() + after.offsets[nextSelection]).toString()
            if (after != before) {
                undoStack = undoStack.appendHistory(before)
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
    val displayMinimum = routeMinimum.orZero()
    val displayMaximum = routeMaximum ?: editorMap.basisSpan
    val editorReady = when {
        snapshot == null -> true
        routeSpan == null || routeSpan <= 0 -> false
        else -> editorMap.basisSpan == routeSpan
    }
    val maximumPressCount = routeSpan ?: editorMap.basisSpan
    val actualIndices = editorMap.actualIndices(snapshot)
    val visiblePressCount = actualIndices.lastIndex
    val safeSelectedStep = selectedStep.coerceIn(0, visiblePressCount)
    val selectedActualIndex = actualIndices[safeSelectedStep]
    val selectedIsEndpoint =
        safeSelectedStep == 0 || safeSelectedStep == visiblePressCount
    val selectedMinimumIndex = if (selectedIsEndpoint || !editorReady) {
        selectedActualIndex
    } else {
        displayMinimum + editorMap.offsets[safeSelectedStep - 1] + 1
    }
    val selectedMaximumIndex = if (selectedIsEndpoint || !editorReady) {
        selectedActualIndex
    } else {
        displayMinimum + editorMap.offsets[safeSelectedStep + 1] - 1
    }
    val selectedCanMove =
        editorReady && !selectedIsEndpoint && selectedMinimumIndex < selectedMaximumIndex

    LaunchedEffect(visiblePressCount) {
        if (selectedStep > visiblePressCount) selectedStep = visiblePressCount
    }
    LaunchedEffect(editorMap.pressCount) {
        pressCountText = editorMap.pressCount.toString()
    }
    LaunchedEffect(safeSelectedStep, actualIndices, displayMinimum) {
        indexText = selectedActualIndex.toString()
    }

    val applyPressCount: () -> Unit = {
        val requested = pressCountText.toIntOrNull()
        if (editorReady && requested != null && requested in 1..maximumPressCount) {
            val before = editorMap
            val logicalSelection = selectedStep.toDouble() / before.pressCount.toDouble()
            val after = before.resample(requested)
            val nextSelection = (logicalSelection * requested).roundToInt()
            publishCompletedAction(before, after, nextSelection)
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
            val after = before.withOffset(safeSelectedStep, requestedIndex - displayMinimum)
            publishCompletedAction(before, after, safeSelectedStep)
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

    val canvasStateDescription = buildString {
        append("从最小到最大需要 ")
        append(visiblePressCount)
        append(" 次短按，共 ")
        append(visiblePressCount + 1)
        append(" 个状态；当前选择第 ")
        append(safeSelectedStep)
        append(" 步，Audio volume index 为 ")
        append(selectedActualIndex)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Y：Audio volume index $displayMinimum…$displayMaximum",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "X：0…$visiblePressCount 次按键（均匀固定）",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp)
                .testTag(CurveEditorTestTags.CANVAS)
                .semantics {
                    contentDescription =
                        "音量映射曲线。横轴是均匀固定的按键次数，纵轴是整数 Audio volume index；可在固定列间拖动画笔"
                    stateDescription = canvasStateDescription
                }
                .pointerInput(
                    editorReady,
                    displayMinimum,
                    displayMaximum,
                    editorMap.pressCount,
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
                        fun stepAt(position: Offset): Int =
                            ((((position.x - left) / plotWidth).coerceIn(0f, 1f)) *
                                editorMap.pressCount.toFloat())
                                .roundToInt()
                                .coerceIn(0, editorMap.pressCount)

                        fun offsetAt(y: Float): Int =
                            ((((plotBottom - y) / plotHeight).coerceIn(0f, 1f)) *
                                editorMap.basisSpan.toFloat())
                                .roundToInt()
                                .coerceIn(0, editorMap.basisSpan)

                        fun paintSegment(from: Offset?, to: Offset) {
                            val toStep = stepAt(to)
                            var paintedMap = editorMap
                            if (from == null) {
                                paintedMap = paintedMap.withOffsetPushing(toStep, offsetAt(to.y))
                            } else {
                                val fromStep = stepAt(from)
                                if (fromStep == toStep) {
                                    paintedMap = paintedMap.withOffsetPushing(
                                        toStep,
                                        offsetAt(to.y),
                                    )
                                } else {
                                    val traversedSteps = if (fromStep < toStep) {
                                        fromStep..toStep
                                    } else {
                                        fromStep downTo toStep
                                    }
                                    traversedSteps.forEach { step ->
                                        val fraction =
                                            (step - fromStep).toFloat() / (toStep - fromStep).toFloat()
                                        val interpolatedY = from.y + (to.y - from.y) * fraction
                                        paintedMap = paintedMap.withOffsetPushing(
                                            step,
                                            offsetAt(interpolatedY),
                                        )
                                    }
                                }
                            }
                            editorMap = paintedMap
                            selectedStep = toStep
                            indexText = (displayMinimum + paintedMap.offsets[toStep]).toString()
                        }

                        val beforeGesture = editorMap
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
                                    selectedStep,
                                )
                                else -> {
                                    editorMap = beforeGesture
                                    pressCountText = beforeGesture.pressCount.toString()
                                    val restoredSelection =
                                        selectedStep.coerceIn(0, beforeGesture.pressCount)
                                    indexText = (
                                        displayMinimum + beforeGesture.offsets[restoredSelection]
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

            fun pointFor(step: Int, index: Int): Offset {
                val xFraction = if (visiblePressCount == 0) {
                    0f
                } else {
                    step.toFloat() / visiblePressCount.toFloat()
                }
                val yFraction = if (indexSpan == 0) {
                    0.5f
                } else {
                    (index - displayMinimum).toFloat() / indexSpan.toFloat()
                }
                return Offset(
                    x = left + xFraction * plotWidth,
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

            val indexPath = Path()
            actualIndices.forEachIndexed { step, index ->
                val point = pointFor(step, index)
                if (step == 0) indexPath.moveTo(point.x, point.y)
                else indexPath.lineTo(point.x, point.y)
            }
            drawPath(
                path = indexPath,
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

            val columnSpacing = plotWidth / visiblePressCount.coerceAtLeast(1).toFloat()
            val pointRadius = (columnSpacing / 4f).coerceIn(2.dp.toPx(), 7.dp.toPx())
            actualIndices.forEachIndexed { step, index ->
                val point = pointFor(step, index)
                val selected = step == safeSelectedStep
                drawCircle(
                    color = if (selected) selectedColor else primaryColor,
                    radius = if (selected) 9.dp.toPx() else pointRadius,
                    center = point,
                )
                if (selected || pointRadius >= 4.dp.toPx()) {
                    drawCircle(
                        color = pointFillColor,
                        radius = if (selected) 4.dp.toPx() else pointRadius / 2f,
                        center = point,
                    )
                }
            }

            val deltas = actualIndices.zipWithNext { leftIndex, rightIndex ->
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

        val selectedDelta = when {
            safeSelectedStep == 0 -> null
            else -> actualIndices[safeSelectedStep] - actualIndices[safeSelectedStep - 1]
        }
        Text(
            text = buildString {
                append("下方条带表示每次短按的 Δindex；色块越高，单次跨度越大。")
                selectedDelta?.let { append(" 当前步 Δindex=+$it。") }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "按键次数 K",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "K 表示从最小音量到最大音量需要的短按次数；曲线始终有 K+1 个等距状态。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (editorReady && editorMap.pressCount == editorMap.basisSpan) {
            Text(
                text = "当前 K 已占满所有整数 index，每次只能 +1，严格曲线因此只有线性一种。先减小 K，才能把更多 Δindex 灵活分配到不同区段。",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = pressCountText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) pressCountText = value
                },
                enabled = editorReady,
                singleLine = true,
                isError = pressCountText.toIntOrNull()?.let {
                    it !in 1..maximumPressCount
                } ?: true,
                label = { Text("短按次数") },
                supportingText = {
                    Text(
                        if (editorReady) "可用范围 1…$maximumPressCount" else "当前路由不可编辑",
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { applyPressCount() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.PRESS_COUNT_INPUT),
            )
            Button(
                onClick = applyPressCount,
                enabled = editorReady &&
                    pressCountText.toIntOrNull()?.let {
                        it in 1..maximumPressCount && it != editorMap.pressCount
                    } == true,
                modifier = Modifier.testTag(CurveEditorTestTags.PRESS_COUNT_APPLY),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("应用次数")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    pressCountText = (editorMap.pressCount - 1).toString()
                    applyPressCount()
                },
                enabled = editorReady && editorMap.pressCount > 1,
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.PRESS_COUNT_DECREMENT),
            ) {
                Text("减少 1 次")
            }
            OutlinedButton(
                onClick = {
                    pressCountText = (editorMap.pressCount + 1).toString()
                    applyPressCount()
                },
                enabled = editorReady && editorMap.pressCount < maximumPressCount,
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.PRESS_COUNT_INCREMENT),
            ) {
                Text("增加 1 次")
            }
        }

        val commonPressCounts = remember(maximumPressCount, editorMap.pressCount) {
            (COMMON_PRESS_COUNTS + editorMap.pressCount + maximumPressCount)
                .filter { it in 1..maximumPressCount }
                .distinct()
                .sorted()
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CurveEditorTestTags.PRESS_COUNT_PRESETS),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(commonPressCounts, key = { it }) { count ->
                FilterChip(
                    selected = count == editorMap.pressCount,
                    enabled = editorReady,
                    onClick = {
                        pressCountText = count.toString()
                        applyPressCount()
                    },
                    label = { Text(count.toString()) },
                )
            }
        }

        Text(
            text = "选择状态：第 $safeSelectedStep / $visiblePressCount 次短按",
            modifier = Modifier.testTag(CurveEditorTestTags.SELECTED_STEP_VALUE),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = safeSelectedStep.toFloat(),
            onValueChange = { value ->
                val nextStep = value.roundToInt().coerceIn(0, visiblePressCount)
                selectedStep = nextStep
                indexText = actualIndices[nextStep].toString()
            },
            enabled = editorReady,
            valueRange = 0f..visiblePressCount.coerceAtLeast(1).toFloat(),
            steps = (visiblePressCount - 1).coerceAtLeast(0),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CurveEditorTestTags.STEP_SELECTOR)
                .semantics {
                    contentDescription = "选择按键状态"
                    stateDescription = "第 $safeSelectedStep 次短按"
                },
        )

        Text(
            text = "第 $safeSelectedStep 步：Audio volume index = $selectedActualIndex",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = if (selectedCanMove) selectedActualIndex.toFloat() else 0f,
            onValueChange = { value ->
                if (sliderActionStart == null) sliderActionStart = editorMap
                val nextIndex = value.roundToInt()
                editorMap = editorMap.withOffset(
                    safeSelectedStep,
                    nextIndex - displayMinimum,
                )
                indexText = (displayMinimum + editorMap.offsets[safeSelectedStep]).toString()
            },
            onValueChangeFinished = {
                val before = sliderActionStart
                sliderActionStart = null
                if (before != null) {
                    publishCompletedAction(before, editorMap, safeSelectedStep)
                }
            },
            enabled = selectedCanMove,
            valueRange = if (selectedCanMove) {
                selectedMinimumIndex.toFloat()..selectedMaximumIndex.toFloat()
            } else {
                0f..1f
            },
            steps = if (selectedCanMove) {
                (selectedMaximumIndex - selectedMinimumIndex - 1).coerceAtLeast(0)
            } else {
                0
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CurveEditorTestTags.INDEX_SLIDER)
                .semantics {
                    contentDescription = "调整第 $safeSelectedStep 步的 Audio volume index"
                    stateDescription = selectedActualIndex.toString()
                },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                label = { Text("Audio volume index") },
                supportingText = {
                    Text(
                        if (selectedIsEndpoint) {
                            "端点固定为 ${if (safeSelectedStep == 0) "最小" else "最大"}音量"
                        } else if (!selectedCanMove) {
                            "相邻状态已占满；可先减小 K，或在画布上推挤多个点"
                        } else {
                            "精确范围 $selectedMinimumIndex…$selectedMaximumIndex"
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
                modifier = Modifier.testTag(CurveEditorTestTags.APPLY),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("应用")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val before = editorMap
                    val after = before.withOffset(
                        safeSelectedStep,
                        selectedActualIndex - 1 - displayMinimum,
                    )
                    publishCompletedAction(before, after, safeSelectedStep)
                },
                enabled = editorReady &&
                    !selectedIsEndpoint &&
                    selectedActualIndex > selectedMinimumIndex,
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.INDEX_DECREMENT),
            ) {
                Text("index −1")
            }
            OutlinedButton(
                onClick = {
                    val before = editorMap
                    val after = before.withOffset(
                        safeSelectedStep,
                        selectedActualIndex + 1 - displayMinimum,
                    )
                    publishCompletedAction(before, after, safeSelectedStep)
                },
                enabled = editorReady &&
                    !selectedIsEndpoint &&
                    selectedActualIndex < selectedMaximumIndex,
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.INDEX_INCREMENT),
            ) {
                Text("index +1")
            }
        }

        val linearMap = if (editorReady) {
            StepVolumeMap.linear(editorMap.basisSpan, editorMap.pressCount)
        } else {
            null
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val current = editorMap
                    val previous = undoStack.last()
                    undoStack = undoStack.dropLast(1)
                    redoStack = redoStack.appendHistory(current)
                    editorMap = previous
                    selectedStep = selectedStep.coerceIn(0, previous.pressCount)
                    pressCountText = previous.pressCount.toString()
                    indexText = (
                        displayMinimum + previous.offsets[selectedStep]
                        ).toString()
                    latestOnMapCommitted(previous)
                },
                enabled = editorReady && undoStack.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.UNDO),
            ) {
                Text("撤销")
            }
            OutlinedButton(
                onClick = {
                    val current = editorMap
                    val next = redoStack.last()
                    redoStack = redoStack.dropLast(1)
                    undoStack = undoStack.appendHistory(current)
                    editorMap = next
                    selectedStep = selectedStep.coerceIn(0, next.pressCount)
                    pressCountText = next.pressCount.toString()
                    indexText = (displayMinimum + next.offsets[selectedStep]).toString()
                    latestOnMapCommitted(next)
                },
                enabled = editorReady && redoStack.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(CurveEditorTestTags.REDO),
            ) {
                Text("重做")
            }
        }
        OutlinedButton(
            onClick = {
                linearMap?.let { resetMap ->
                    publishCompletedAction(editorMap, resetMap, selectedStep)
                }
            },
            enabled = linearMap != null && linearMap != editorMap,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CurveEditorTestTags.RESET_LINEAR),
        ) {
            Text("重置为线性曲线")
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

private fun StepVolumeMap.forEditing(snapshot: RouteVolumeSnapshot?): StepVolumeMap {
    val range = snapshot?.range ?: return this
    val routeSpan = range.maxIndex - range.minIndex
    if (routeSpan <= 0) return this
    return if (basisSpan == routeSpan) this else rebase(range)
}

private fun StepVolumeMap.actualIndices(snapshot: RouteVolumeSnapshot?): List<Int> {
    val range = snapshot?.range ?: return offsets
    val routeSpan = range.maxIndex - range.minIndex
    if (routeSpan == 0) return List(pressCount + 1) { range.minIndex }
    return if (basisSpan == routeSpan) {
        offsets.map { range.minIndex + it }
    } else {
        bind(range).indices
    }
}

private fun List<StepVolumeMap>.appendHistory(map: StepVolumeMap): List<StepVolumeMap> =
    (this + map).takeLast(HISTORY_LIMIT)

private fun Int?.orZero(): Int = this ?: 0

private const val HISTORY_LIMIT = 50

private val COMMON_PRESS_COUNTS = listOf(5, 8, 10, 15, 20, 30, 50, 75, 100, 150)

object CurveEditorTestTags {
    const val CANVAS = "mapping_curve_canvas"
    const val PRESS_COUNT_INPUT = "mapping_curve_press_count_input"
    const val PRESS_COUNT_DECREMENT = "mapping_curve_press_count_decrement"
    const val PRESS_COUNT_INCREMENT = "mapping_curve_press_count_increment"
    const val PRESS_COUNT_APPLY = "mapping_curve_press_count_apply"
    const val PRESS_COUNT_PRESETS = "mapping_curve_press_count_presets"
    const val STEP_SELECTOR = "mapping_curve_step_selector"
    const val SELECTED_STEP_VALUE = "mapping_curve_selected_step_value"
    const val INDEX_SLIDER = "mapping_curve_index_slider"
    const val INDEX_INPUT = "mapping_curve_index_input"
    const val INDEX_DECREMENT = "mapping_curve_index_decrement"
    const val INDEX_INCREMENT = "mapping_curve_index_increment"
    const val APPLY = "mapping_curve_apply"
    const val UNDO = "mapping_curve_undo"
    const val REDO = "mapping_curve_redo"
    const val RESET_LINEAR = "mapping_curve_reset_linear"

    // Kept as source-compatible aliases while existing UI tests migrate to integer-step terms.
    const val POINT_SELECTOR = STEP_SELECTOR
    const val SELECTED_POINT_VALUE = SELECTED_STEP_VALUE
    const val X_SLIDER = PRESS_COUNT_INPUT
    const val Y_SLIDER = INDEX_SLIDER

    fun controlPoint(index: Int): String = "mapping_curve_control_point_$index"
}
