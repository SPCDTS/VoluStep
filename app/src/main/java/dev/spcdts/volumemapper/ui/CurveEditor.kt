package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
                drawMappingCurve(
                    CurveRenderModel(
                        renderedMap = renderedMap,
                        displayedControlIndices = displayedControlIndices,
                        selectedIndex = selectedIndex,
                        selectedSegmentIndex = selectedSegmentIndex,
                        selectedDisplayIndex = selectedDisplayIndex,
                        displayMinimum = displayMinimum,
                        displayMaximum = displayMaximum,
                        displaySpan = displaySpan,
                        displayDenominator = displayDenominator,
                        currentDisplayIndex = currentDisplayIndex,
                        currentX = currentX,
                        currentBadgeText = currentBadgeText,
                        leftPaddingPx = leftPaddingPx,
                        rightPaddingPx = rightPaddingPx,
                        topPaddingPx = topPaddingPx,
                        bottomPaddingPx = bottomPaddingPx,
                        tick = tick,
                        grid = grid,
                        curve = curve,
                        current = current,
                        currentContent = currentContent,
                        selection = selection,
                        surface = surface,
                    ),
                )
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
