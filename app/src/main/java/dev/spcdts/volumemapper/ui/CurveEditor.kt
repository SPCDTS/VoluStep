package dev.spcdts.volumemapper.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.StepVolumeMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Piecewise-linear volume map editor.
 *
 * Control points are authored on the integer press/index grid. K + 1 short-press positions stay
 * uniformly distributed over the x axis, while the independently configurable control-point count
 * is limited by the number of available integer press positions.
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
    var selectedSegment by remember { mutableStateOf<Int?>(null) }
    var gestureInProgress by remember { mutableStateOf(false) }
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
            val previousMap = editorMap
            val previousSegmentIndex = selectedSegment?.coerceIn(
                0,
                previousMap.controlSegmentCount - 1,
            )
            val previousPointIndex = selectedControlPoint.coerceIn(
                previousMap.normalizedXs.indices,
            )
            val previousSegmentMidpoint = previousSegmentIndex?.let { safeSegment ->
                (editorMap.normalizedXAt(safeSegment) +
                    editorMap.normalizedXAt(safeSegment + 1)) / 2.0
            }
            val previousX = if (previousSegmentMidpoint == null) {
                editorMap.normalizedXAt(
                    selectedControlPoint.coerceIn(editorMap.normalizedXs.indices),
                )
            } else {
                previousSegmentMidpoint
            }
            editorMap = outputMap
            if (previousSegmentIndex != null) {
                selectedSegment = if (
                    outputMap.controlSegmentCount == previousMap.controlSegmentCount
                ) {
                    previousSegmentIndex
                } else {
                    outputMap.closestSegmentIndex(previousX)
                }
            } else {
                selectedControlPoint = if (
                    outputMap.controlPointCount == previousMap.controlPointCount
                ) {
                    previousPointIndex
                } else {
                    outputMap.closestControlPointIndex(previousX)
                }
                selectedSegment = null
            }
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

    fun insertionSegmentIndexFor(map: StepVolumeMap): Int {
        selectedSegment?.let { return it.coerceIn(0, map.controlSegmentCount - 1) }
        val pointIndex = selectedControlPoint.coerceIn(map.normalizedXs.indices)
        return pointIndex.coerceAtMost(map.controlSegmentCount - 1)
    }

    fun maximumControlPointCountFor(map: StepVolumeMap): Int = minOf(
        MAXIMUM_CONTROL_POINT_COUNT,
        map.pressCount + 1,
        map.basisSpan + 1,
    )

    fun canInsertAtSegment(map: StepVolumeMap, segmentIndex: Int): Boolean =
        map.pressPositionAt(segmentIndex + 1) - map.pressPositionAt(segmentIndex) >= 2 &&
            map.offsets[segmentIndex + 1] - map.offsets[segmentIndex] >= 2 &&
            map.controlPointCount < maximumControlPointCountFor(map)

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
    val currentX = currentDisplayIndex?.let { index ->
        renderedMap.normalizedXForDisplayedIndex(displayedControlIndices, index)
    }
    val selectedIndex = selectedControlPoint.coerceIn(renderedMap.normalizedXs.indices)
    val selectedSegmentIndex = selectedSegment?.coerceIn(
        0,
        renderedMap.controlSegmentCount - 1,
    )
    val maximumControlPointCount = maximumControlPointCountFor(renderedMap)
    val canDeleteSelectedPoint = selectedSegmentIndex == null &&
        selectedIndex in 1 until renderedMap.controlSegmentCount &&
        renderedMap.controlPointCount > 2
    val selectedInsertionSegmentIndex = insertionSegmentIndexFor(renderedMap)
    val canInsertAtSelection = canInsertAtSegment(renderedMap, selectedInsertionSegmentIndex)
    val density = LocalDensity.current
    val chartHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.42f)
        .coerceIn(280.dp, 360.dp)
    val leftPaddingPx = with(density) { CurveEditorGeometry.PLOT_LEFT_PADDING_DP.dp.toPx() }
    val rightPaddingPx = with(density) { CurveEditorGeometry.PLOT_RIGHT_PADDING_DP.dp.toPx() }
    val topPaddingPx = with(density) { CurveEditorGeometry.PLOT_TOP_PADDING_DP.dp.toPx() }
    val bottomPaddingPx = with(density) { CurveEditorGeometry.PLOT_BOTTOM_PADDING_DP.dp.toPx() }
    val pointCoreRadiusPx = with(density) { 8.dp.toPx() }
    val hitRadiusPx = with(density) { 22.dp.toPx() }
    val segmentHitRadiusPx = with(density) { 14.dp.toPx() }

    val primary = MaterialTheme.colorScheme.primary
    val curve = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val selection = MaterialTheme.colorScheme.tertiary
    val current = primary
    val currentContent = MaterialTheme.colorScheme.onPrimary

    fun publish(
        next: StepVolumeMap,
        preferredX: Double,
        preferredControlPointIndex: Int? = null,
        preferredSegmentIndex: Int? = null,
    ) {
        if (next == editorMap) return
        editorMap = next
        if (preferredSegmentIndex != null) {
            selectedSegment = preferredSegmentIndex.coerceIn(0, next.controlSegmentCount - 1)
        } else {
            selectedControlPoint = preferredControlPointIndex
                ?.coerceIn(next.normalizedXs.indices)
                ?: next.closestControlPointIndex(preferredX)
            selectedSegment = null
        }
        latestOnMapCommitted(next)
    }

    fun insertAtSelection(): Boolean {
        val before = editorMap
        val segmentIndex = insertionSegmentIndexFor(before)
        if (!editorReady || !canInsertAtSegment(before, segmentIndex)) {
            return false
        }
        val after = before.insertControlPointAtSegment(segmentIndex)
        editorMap = after
        selectedControlPoint = segmentIndex + 1
        selectedSegment = null
        latestOnMapCommitted(after)
        return true
    }

    fun deleteSelectedControlPoint(): Boolean {
        val before = editorMap
        if (!editorReady || selectedSegment != null || before.controlPointCount <= 2) return false
        val pointIndex = selectedControlPoint.coerceIn(before.normalizedXs.indices)
        if (pointIndex == 0 || pointIndex == before.controlSegmentCount) return false
        val after = before.removeControlPointAt(pointIndex)
        editorMap = after
        selectedSegment = (pointIndex - 1).coerceIn(0, after.controlSegmentCount - 1)
        selectedControlPoint = pointIndex.coerceIn(after.normalizedXs.indices)
        latestOnMapCommitted(after)
        return true
    }

    fun moveSelectedControlPoint(
        pressDelta: Int = 0,
        displayIndexDelta: Int = 0,
    ): Boolean {
        val before = editorMap
        if (selectedSegment != null) return false
        val pointIndex = selectedControlPoint.coerceIn(before.normalizedXs.indices)
        if (!editorReady || pointIndex == 0 || pointIndex == before.controlSegmentCount) {
            return false
        }

        val currentPressPosition = before.pressPositionAt(pointIndex)
        val requestedPressPosition = if (pressDelta == 0) {
            currentPressPosition
        } else {
            val candidate = currentPressPosition + pressDelta
            val minimum = before.pressPositionAt(pointIndex - 1) + 1
            val maximum = before.pressPositionAt(pointIndex + 1) - 1
            if (candidate !in minimum..maximum) return false
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
            requestedPressPosition = requestedPressPosition,
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
        selectedSegment = null
        latestOnMapCommitted(after)
        return true
    }

    val selectedDisplayIndex = displayIndexForOffset(
        renderedMap,
        renderedMap.offsets[selectedIndex],
    )
    val controlPointsLabel = stringResource(R.string.curve_control_points)
    val buttonPressesLabel = stringResource(R.string.curve_button_presses)
    val selectPreviousPointAction = stringResource(
        R.string.curve_action_select_previous_point,
    )
    val selectNextPointAction = stringResource(R.string.curve_action_select_next_point)
    val selectPreviousSegmentAction = stringResource(
        R.string.curve_action_select_previous_segment,
    )
    val selectNextSegmentAction = stringResource(R.string.curve_action_select_next_segment)
    val movePointLeftAction = stringResource(R.string.curve_action_move_point_left)
    val movePointRightAction = stringResource(R.string.curve_action_move_point_right)
    val movePointUpAction = stringResource(R.string.curve_action_move_point_up)
    val movePointDownAction = stringResource(R.string.curve_action_move_point_down)
    val deletePointAction = stringResource(R.string.curve_action_delete_point)
    val insertPointAction = stringResource(R.string.curve_action_insert_point)
    val chartDescription = stringResource(R.string.curve_chart_description)
    val currentVolumeLineDescription = if (snapshot != null) {
        stringResource(R.string.curve_current_volume_line, snapshot.currentIndex)
    } else {
        null
    }
    val chartContentDescription = listOfNotNull(
        chartDescription,
        currentVolumeLineDescription,
    ).joinToString(separator = " ")
    val summaryStateDescription = stringResource(
        R.string.curve_state_summary,
        renderedMap.pressCount,
        renderedMap.controlPointCount,
    )
    val selectionStateDescriptions = if (selectedSegmentIndex == null) {
        listOf(
            stringResource(R.string.curve_state_selected_point, selectedIndex + 1),
            stringResource(
                R.string.curve_state_x,
                renderedMap.pressPositionAt(selectedIndex),
            ),
            stringResource(R.string.curve_state_index, selectedDisplayIndex),
            stringResource(R.string.curve_state_vertical_hint),
        )
    } else {
        listOf(
            stringResource(
                R.string.curve_state_selected_segment,
                selectedSegmentIndex + 1,
            ),
            stringResource(
                R.string.curve_state_x_range,
                renderedMap.pressPositionAt(selectedSegmentIndex),
                renderedMap.pressPositionAt(selectedSegmentIndex + 1),
            ),
            stringResource(
                R.string.curve_state_index_range,
                displayedControlIndices[selectedSegmentIndex],
                displayedControlIndices[selectedSegmentIndex + 1],
            ),
        )
    }
    val currentVolumeStateDescription = if (snapshot != null) {
        stringResource(R.string.curve_state_current_volume, snapshot.currentIndex)
    } else {
        null
    }
    val chartStateDescription = buildList {
        add(summaryStateDescription)
        addAll(selectionStateDescriptions)
        currentVolumeStateDescription?.let(::add)
    }.joinToString(separator = " ")
    val currentBadgeText = if (snapshot != null) {
        stringResource(R.string.curve_current_badge, snapshot.currentIndex)
    } else {
        null
    }
    val noAdjustableLevelsText = stringResource(R.string.curve_no_adjustable_levels)
    val chartCustomActions = buildList {
        add(
            CustomAccessibilityAction(selectPreviousPointAction) {
                val base = selectedSegment?.plus(1) ?: selectedControlPoint
                if (base <= 0) {
                    false
                } else {
                    selectedControlPoint = base - 1
                    selectedSegment = null
                    true
                }
            },
        )
        add(
            CustomAccessibilityAction(selectNextPointAction) {
                val base = selectedSegment ?: selectedControlPoint
                if (base >= editorMap.controlSegmentCount) {
                    false
                } else {
                    selectedControlPoint = base + 1
                    selectedSegment = null
                    true
                }
            },
        )
        add(
            CustomAccessibilityAction(selectPreviousSegmentAction) {
                val target = selectedSegment?.minus(1) ?: (selectedControlPoint - 1)
                if (target !in 0 until editorMap.controlSegmentCount) {
                    false
                } else {
                    selectedSegment = target
                    true
                }
            },
        )
        add(
            CustomAccessibilityAction(selectNextSegmentAction) {
                val target = selectedSegment?.plus(1) ?: selectedControlPoint
                if (target !in 0 until editorMap.controlSegmentCount) {
                    false
                } else {
                    selectedSegment = target
                    true
                }
            },
        )
        if (selectedSegmentIndex == null && canDeleteSelectedPoint && editorReady) {
            add(
                CustomAccessibilityAction(movePointLeftAction) {
                    moveSelectedControlPoint(pressDelta = -1)
                },
            )
            add(
                CustomAccessibilityAction(movePointRightAction) {
                    moveSelectedControlPoint(pressDelta = 1)
                },
            )
            add(
                CustomAccessibilityAction(movePointUpAction) {
                    moveSelectedControlPoint(displayIndexDelta = 1)
                },
            )
            add(
                CustomAccessibilityAction(movePointDownAction) {
                    moveSelectedControlPoint(displayIndexDelta = -1)
                },
            )
            add(CustomAccessibilityAction(deletePointAction) { deleteSelectedControlPoint() })
        }
        if (canInsertAtSelection && editorReady) {
            add(CustomAccessibilityAction(insertPointAction) { insertAtSelection() })
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompactCurveStepper(
            label = controlPointsLabel,
            value = renderedMap.controlPointCount,
            minimum = 2,
            maximum = maximumControlPointCount,
            enabled = editorReady,
            valueEditable = false,
            canDecrement = canDeleteSelectedPoint,
            canIncrement = canInsertAtSelection,
            onValueChange = { requested ->
                when {
                    requested < renderedMap.controlPointCount -> deleteSelectedControlPoint()
                    requested > renderedMap.controlPointCount -> insertAtSelection()
                }
            },
            valueTag = CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT,
            decrementTag = CurveEditorTestTags.CONTROL_POINT_COUNT_DECREMENT,
            incrementTag = CurveEditorTestTags.CONTROL_POINT_COUNT_INCREMENT,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .testTag(CurveEditorTestTags.CURRENT_VOLUME_MARKER)
                .semantics {
                    contentDescription = chartContentDescription
                    stateDescription = chartStateDescription
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

                            fun segmentDistanceSquared(
                                start: Offset,
                                end: Offset,
                                target: Offset,
                            ): Float? {
                                val dx = end.x - start.x
                                val dy = end.y - start.y
                                val lengthSquared = dx * dx + dy * dy
                                if (lengthSquared <= 0f) return null
                                val projection = (
                                    (target.x - start.x) * dx + (target.y - start.y) * dy
                                    ) / lengthSquared
                                if (projection !in 0.08f..0.92f) return null
                                val projected = Offset(
                                    x = start.x + projection * dx,
                                    y = start.y + projection * dy,
                                )
                                return squaredDistance(projected, target)
                            }

                            val pointPositions = startMap.normalizedXs.indices.map { index ->
                                pointFor(startMap, index)
                            }
                            val coreRadiusSquared = pointCoreRadiusPx * pointCoreRadiusPx
                            val hitRadiusSquared = hitRadiusPx * hitRadiusPx
                            val corePointIndex = pointPositions.indices
                                .minBy { squaredDistance(pointPositions[it], down.position) }
                                .takeIf {
                                    squaredDistance(pointPositions[it], down.position) <=
                                        coreRadiusSquared
                                }
                            val segmentRadiusSquared = segmentHitRadiusPx * segmentHitRadiusPx
                            val hitSegmentIndex = if (corePointIndex == null) {
                                (0 until startMap.controlSegmentCount)
                                    .mapNotNull { segmentIndex ->
                                        segmentDistanceSquared(
                                            pointPositions[segmentIndex],
                                            pointPositions[segmentIndex + 1],
                                            down.position,
                                        )?.let { distance -> segmentIndex to distance }
                                    }
                                    .minByOrNull { it.second }
                                    ?.takeIf { it.second <= segmentRadiusSquared }
                                    ?.first
                            } else {
                                null
                            }
                            if (hitSegmentIndex != null) {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    selectedSegment = hitSegmentIndex
                                    up.consume()
                                }
                                return@awaitEachGesture
                            }
                            val hitIndex = corePointIndex ?: pointPositions.indices
                                .minBy { squaredDistance(pointPositions[it], down.position) }
                                .takeIf {
                                    squaredDistance(pointPositions[it], down.position) <=
                                        hitRadiusSquared
                                }
                                ?: return@awaitEachGesture

                            selectedControlPoint = hitIndex
                            selectedSegment = null
                            if (hitIndex == 0 || hitIndex == startMap.controlSegmentCount) {
                                return@awaitEachGesture
                            }
                            val canMoveX =
                                startMap.pressPositionAt(hitIndex) >
                                startMap.pressPositionAt(hitIndex - 1) + 1 ||
                                    startMap.pressPositionAt(hitIndex) <
                                    startMap.pressPositionAt(hitIndex + 1) - 1
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
                                        val requestedPressPosition =
                                            (rawX * gestureMap.pressCount).roundToInt().coerceIn(
                                                gestureMap.pressPositionAt(hitIndex - 1) + 1,
                                                gestureMap.pressPositionAt(hitIndex + 1) - 1,
                                            )
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
                                        val currentOffset = gestureMap.offsets[hitIndex]
                                        val currentDisplayedIndex = displayIndexForOffset(
                                            gestureMap,
                                            currentOffset,
                                        )
                                        val requestedOffset = if (
                                            requestedDisplayIndex == currentDisplayedIndex
                                        ) {
                                            // Display-index projection can be many-to-one when a
                                            // route has fewer steps than the editing basis. Keep
                                            // the authored y exactly stable during a horizontal
                                            // drag instead of round-tripping (for example 5 -> 3
                                            // -> 6 on a 30-to-15 route).
                                            currentOffset
                                        } else {
                                            referenceOffsetForDisplayIndex(
                                                gestureMap,
                                                requestedDisplayIndex,
                                            )
                                        }
                                        val moved = gestureMap.moveControlPointPushing(
                                            controlPointIndex = hitIndex,
                                            requestedPressPosition = requestedPressPosition,
                                            requestedOffset = requestedOffset,
                                        )
                                        gestureMap = moved
                                        editorMap = moved
                                        selectedControlPoint = hitIndex
                                        selectedSegment = null
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
                                    selectedSegment = null
                                }
                                gestureInProgress = false
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
                val controlPoints = displayedControlIndices.mapIndexed { index, displayIndex ->
                    pointFor(renderedMap.normalizedXAt(index), displayIndex.toDouble())
                }
                val selectedPoint = controlPoints[selectedIndex]
                val selectedPointVisible = selectedSegmentIndex == null
                val selectedPressPosition = renderedMap.pressPositionAt(selectedIndex)
                val axisValues = listOf(
                    displayMinimum,
                    displayMinimum + (displaySpan / 3.0).roundToInt(),
                    displayMinimum + (displaySpan * 2.0 / 3.0).roundToInt(),
                    displayMaximum,
                ).distinct()

                fun drawVolumeGlyph(centerY: Float, value: Int) {
                    val color = tick.copy(alpha = 0.88f)
                    val stroke = 1.35.dp.toPx()
                    val originX = plotLeft - 54.dp.toPx()
                    val speaker = Path().apply {
                        moveTo(originX, centerY - 2.2.dp.toPx())
                        lineTo(originX + 3.2.dp.toPx(), centerY - 2.2.dp.toPx())
                        lineTo(originX + 7.2.dp.toPx(), centerY - 5.2.dp.toPx())
                        lineTo(originX + 7.2.dp.toPx(), centerY + 5.2.dp.toPx())
                        lineTo(originX + 3.2.dp.toPx(), centerY + 2.2.dp.toPx())
                        lineTo(originX, centerY + 2.2.dp.toPx())
                        close()
                    }
                    drawPath(speaker, color = color, style = Stroke(width = stroke))
                    if (value <= 0) {
                        val muteLeft = originX + 10.dp.toPx()
                        val muteRight = originX + 16.dp.toPx()
                        drawLine(
                            color = color,
                            start = Offset(muteLeft, centerY - 3.dp.toPx()),
                            end = Offset(muteRight, centerY + 3.dp.toPx()),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = color,
                            start = Offset(muteRight, centerY - 3.dp.toPx()),
                            end = Offset(muteLeft, centerY + 3.dp.toPx()),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        return
                    }
                    val level = if (displaySpan <= 0) {
                        1
                    } else {
                        when {
                            value - displayMinimum <= displaySpan / 3.0 -> 1
                            value - displayMinimum <= displaySpan * 2.0 / 3.0 -> 2
                            else -> 3
                        }
                    }
                    repeat(level) { wave ->
                        val radius = (3.0f + wave * 2.6f).dp.toPx()
                        val waveX = originX + (9.2f + wave * 1.4f).dp.toPx()
                        val wavePath = Path().apply {
                            moveTo(waveX, centerY - radius)
                            quadraticTo(
                                waveX + radius * 0.78f,
                                centerY,
                                waveX,
                                centerY + radius,
                            )
                        }
                        drawPath(wavePath, color = color, style = Stroke(width = stroke))
                    }
                }

                axisValues.forEach { value ->
                    val y = pointFor(0.0, value.toDouble()).y
                    drawLine(
                        color = grid.copy(
                            alpha = if (value == displayMinimum) 0.72f else 0.48f,
                        ),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawVolumeGlyph(y, value)
                    if (!selectedPointVisible || abs(y - selectedPoint.y) >= 14.dp.toPx()) {
                        drawContext.canvas.nativeCanvas.drawText(
                            value.toString(),
                            plotLeft - 8.dp.toPx(),
                            y - (tickPaint.ascent() + tickPaint.descent()) / 2f,
                            tickPaint,
                        )
                    }
                }
                drawLine(
                    color = grid.copy(alpha = 0.8f),
                    start = Offset(plotLeft, plotTop),
                    end = Offset(plotLeft, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                )

                val xTickPaint = AndroidPaint(tickPaint).apply {
                    textAlign = AndroidPaint.Align.CENTER
                }
                val xAxisLabelBaseline = plotBottom + 17.dp.toPx()
                listOf(
                    0,
                    (renderedMap.pressCount / 2.0).roundToInt(),
                    renderedMap.pressCount,
                ).distinct().forEach { pressPosition ->
                    val x = plotLeft +
                        pressPosition.toFloat() / renderedMap.pressCount.toFloat() * plotWidth
                    drawLine(
                        color = grid.copy(alpha = 0.65f),
                        start = Offset(x, plotBottom),
                        end = Offset(x, plotBottom + 4.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                    if (!selectedPointVisible || abs(x - selectedPoint.x) >= 18.dp.toPx()) {
                        xTickPaint.textAlign = when (pressPosition) {
                            0 -> AndroidPaint.Align.LEFT
                            renderedMap.pressCount -> AndroidPaint.Align.RIGHT
                            else -> AndroidPaint.Align.CENTER
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            pressPosition.toString(),
                            x,
                            xAxisLabelBaseline,
                            xTickPaint,
                        )
                    }
                }

                val area = Path().apply {
                    moveTo(controlPoints.first().x, plotBottom)
                    lineTo(controlPoints.first().x, controlPoints.first().y)
                    controlPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    lineTo(controlPoints.last().x, plotBottom)
                    close()
                }
                drawPath(area, color = curve.copy(alpha = 0.055f))

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
                val selectedGuideEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                )
                if (selectedPointVisible) {
                    drawLine(
                        color = selection.copy(alpha = 0.72f),
                        start = Offset(plotLeft, selectedPoint.y),
                        end = selectedPoint,
                        strokeWidth = 1.35.dp.toPx(),
                        pathEffect = selectedGuideEffect,
                    )
                    drawLine(
                        color = selection.copy(alpha = 0.72f),
                        start = selectedPoint,
                        end = Offset(selectedPoint.x, plotBottom),
                        strokeWidth = 1.35.dp.toPx(),
                        pathEffect = selectedGuideEffect,
                    )
                }

                val controlPath = Path().apply {
                    controlPoints.forEachIndexed { index, point ->
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                drawPath(
                    path = controlPath,
                    color = curve,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                selectedSegmentIndex?.let { segmentIndex ->
                    val start = controlPoints[segmentIndex]
                    val end = controlPoints[segmentIndex + 1]
                    drawLine(
                        color = selection.copy(alpha = 0.14f),
                        start = start,
                        end = end,
                        strokeWidth = 17.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = selection.copy(alpha = 0.32f),
                        start = start,
                        end = end,
                        strokeWidth = 10.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = selection.copy(alpha = 0.82f),
                        start = start,
                        end = end,
                        strokeWidth = 5.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = curve,
                        start = start,
                        end = end,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                val currentIntersection = if (
                    currentY != null && currentX != null && currentBadgeText != null
                ) {
                    Offset(
                        x = plotLeft + currentX.toFloat() * plotWidth,
                        y = currentY,
                    )
                } else {
                    null
                }
                controlPoints.forEachIndexed { index, point ->
                    val isSelected = selectedPointVisible && index == selectedIndex
                    if (isSelected) {
                        val glowRadius = 17.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to selection.copy(alpha = 0.52f),
                                    0.45f to selection.copy(alpha = 0.32f),
                                    0.72f to selection.copy(alpha = 0.16f),
                                    1f to selection.copy(alpha = 0f),
                                ),
                                center = point,
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = point,
                        )
                        drawCircle(
                            color = selection.copy(alpha = 0.95f),
                            radius = 7.dp.toPx(),
                            center = point,
                            style = Stroke(width = 1.8.dp.toPx()),
                        )
                    }
                    drawCircle(
                        color = surface,
                        radius = 4.2.dp.toPx(),
                        center = point,
                    )
                    drawCircle(
                        color = curve,
                        radius = 4.2.dp.toPx(),
                        center = point,
                        style = Stroke(width = 1.6.dp.toPx()),
                    )
                }

                currentIntersection?.let { intersection ->
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
                }

                if (selectedPointVisible) {
                    drawLine(
                        color = selection,
                        start = Offset(plotLeft - 5.dp.toPx(), selectedPoint.y),
                        end = Offset(plotLeft, selectedPoint.y),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawLine(
                        color = selection,
                        start = Offset(selectedPoint.x, plotBottom),
                        end = Offset(selectedPoint.x, plotBottom + 5.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    val selectedTickPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = selection.toArgb()
                        textSize = 11.sp.toPx()
                        typeface = android.graphics.Typeface.create(
                            "sans-serif-medium",
                            android.graphics.Typeface.NORMAL,
                        )
                    }
                    selectedTickPaint.textAlign = AndroidPaint.Align.RIGHT
                    drawContext.canvas.nativeCanvas.drawText(
                        selectedDisplayIndex.toString(),
                        plotLeft - 8.dp.toPx(),
                        selectedPoint.y -
                            (selectedTickPaint.ascent() + selectedTickPaint.descent()) / 2f,
                        selectedTickPaint,
                    )
                    selectedTickPaint.textAlign = when {
                        selectedPressPosition == 0 -> AndroidPaint.Align.LEFT
                        selectedPressPosition == renderedMap.pressCount -> AndroidPaint.Align.RIGHT
                        else -> AndroidPaint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        selectedPressPosition.toString(),
                        selectedPoint.x,
                        xAxisLabelBaseline,
                        selectedTickPaint,
                    )
                }

                if (currentY != null && currentX != null && currentBadgeText != null) {
                    val tagText = currentBadgeText
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
                    val rightAlignedTagLeft = (plotRight - tagWidth).coerceAtLeast(plotLeft)
                    val currentIntersectionX = plotLeft + currentX.toFloat() * plotWidth
                    val tagLeft = if (
                        currentIntersectionX >= rightAlignedTagLeft - 6.dp.toPx()
                    ) {
                        // Near maximum volume the curve intersection and endpoint sit at the
                        // right edge. Put the tag on the opposite side so it never hides them.
                        plotLeft
                    } else {
                        rightAlignedTagLeft
                    }
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
        }

        CompactCurveStepper(
            label = buttonPressesLabel,
            value = renderedMap.pressCount,
            minimum = renderedMap.controlSegmentCount,
            maximum = renderedMap.basisSpan,
            enabled = editorReady,
            onValueChange = { requested ->
                publish(
                    renderedMap.withPressCount(requested),
                    renderedMap.normalizedXAt(selectedIndex),
                    preferredControlPointIndex = selectedIndex.takeIf {
                        selectedSegmentIndex == null
                    },
                    preferredSegmentIndex = selectedSegmentIndex,
                )
            },
            valueTag = CurveEditorTestTags.PRESS_COUNT_INPUT,
            decrementTag = CurveEditorTestTags.PRESS_COUNT_DECREMENT,
            incrementTag = CurveEditorTestTags.PRESS_COUNT_INCREMENT,
        )

        if (snapshot != null && snapshot.range.minIndex == snapshot.range.maxIndex) {
            Text(
                text = noAdjustableLevelsText,
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
    valueEditable: Boolean = true,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    onValueChange: (Int) -> Unit,
    valueTag: String,
    decrementTag: String,
    incrementTag: String,
) {
    var draftValue by remember { mutableStateOf(value.toString()) }
    var inputHasFocus by remember { mutableStateOf(false) }
    var draftEdited by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurface
    val emptyValueDescription = stringResource(R.string.curve_empty_value)
    val decreaseDescription = if (valueEditable) {
        stringResource(R.string.curve_decrease_value, label)
    } else {
        stringResource(R.string.curve_action_delete_point)
    }
    val increaseDescription = if (valueEditable) {
        stringResource(R.string.curve_increase_value, label)
    } else {
        stringResource(R.string.curve_action_insert_point)
    }

    LaunchedEffect(value, inputHasFocus, draftEdited) {
        if (!inputHasFocus || !draftEdited) {
            draftValue = value.toString()
            draftEdited = false
        }
    }

    fun commitDraft() {
        if (!valueEditable) {
            draftValue = value.toString()
            draftEdited = false
            return
        }
        if (!draftEdited) {
            draftValue = value.toString()
            return
        }
        val requested = draftValue.toIntOrNull()
        if (requested == null) {
            draftValue = value.toString()
            draftEdited = false
            return
        }
        val accepted = requested.coerceIn(minimum, maximum)
        draftValue = accepted.toString()
        draftEdited = false
        if (accepted != value) onValueChange(accepted)
    }

    fun updateFromButton(delta: Int) {
        val base = if (valueEditable) {
            draftValue.toIntOrNull()?.coerceIn(minimum, maximum) ?: value
        } else {
            value
        }
        val accepted = (base + delta).coerceIn(minimum, maximum)
        draftValue = accepted.toString()
        draftEdited = false
        if (accepted != value) onValueChange(accepted)
        inputHasFocus = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val buttonBase = if (valueEditable) {
        draftValue.toIntOrNull()?.coerceIn(minimum, maximum) ?: value
    } else {
        value
    }

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
            enabled = enabled && canDecrement && buttonBase > minimum,
            modifier = Modifier
                .size(40.dp)
                .testTag(decrementTag),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = decreaseDescription,
                modifier = Modifier.size(18.dp),
            )
        }
        if (valueEditable) {
            BasicTextField(
                value = draftValue,
                onValueChange = { requested ->
                    if (requested.all(Char::isDigit) && requested.length <= 9) {
                        draftValue = requested
                        draftEdited = true
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
                        stateDescription = draftValue.ifBlank { emptyValueDescription }
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
        } else {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(48.dp)
                    .testTag(valueTag)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = value.toString()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value.toString(),
                    color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        IconButton(
            onClick = { updateFromButton(1) },
            enabled = enabled && canIncrement && buttonBase < maximum,
            modifier = Modifier
                .size(40.dp)
                .testTag(incrementTag),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = increaseDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val MAXIMUM_CONTROL_POINT_COUNT = 16

private fun StepVolumeMap.closestSegmentIndex(normalizedX: Double): Int {
    require(normalizedX.isFinite())
    val requestedX = normalizedX.coerceIn(0.0, 1.0)
    return (0 until controlSegmentCount).minBy { segmentIndex ->
        val midpoint = (normalizedXAt(segmentIndex) + normalizedXAt(segmentIndex + 1)) / 2.0
        abs(midpoint - requestedX)
    }
}

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
