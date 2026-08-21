package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.spcdts.volumemapper.core.MappingCurve
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import dev.spcdts.volumemapper.core.VolumeQuantizationMode
import dev.spcdts.volumemapper.core.VolumeQuantizer
import java.util.Locale
import kotlin.math.hypot

@Composable
fun MappingCurveEditor(
    curve: MappingCurve,
    snapshot: RouteVolumeSnapshot?,
    quantizationMode: VolumeQuantizationMode,
    currentLogicalPosition: Double?,
    onCurveChanged: (MappingCurve) -> Unit,
    modifier: Modifier = Modifier,
    onCurveChangeFinished: () -> Unit = {},
) {
    var workingCurve by remember { mutableStateOf(curve) }
    var selectedIndex by remember { mutableIntStateOf(1.coerceAtMost(curve.points.lastIndex)) }
    var dragging by remember { mutableStateOf(false) }
    var pendingExternalCurve by remember { mutableStateOf<MappingCurve?>(null) }
    val paddingPx = with(LocalDensity.current) { 24.dp.toPx() }
    val hitRadiusPx = with(LocalDensity.current) { 28.dp.toPx() }

    LaunchedEffect(curve, dragging) {
        if (dragging) {
            pendingExternalCurve = curve
        } else {
            val synchronizedCurve = pendingExternalCurve ?: curve
            workingCurve = synchronizedCurve
            selectedIndex = selectedIndex.coerceIn(0, synchronizedCurve.points.lastIndex)
            pendingExternalCurve = null
        }
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .testTag(CurveEditorTestTags.CANVAS)
                .semantics {
                    contentDescription = "音量映射曲线，可拖动控制点；横轴是逻辑按键位置，纵轴是目标媒体音量"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val usableWidth = (size.width - 2f * paddingPx).coerceAtLeast(1f)
                        val usableHeight = (size.height - 2f * paddingPx).coerceAtLeast(1f)
                        val nearestIndex = workingCurve.points.indices.minBy { index ->
                            val point = workingCurve.points[index]
                            val px = paddingPx + point.x.toFloat() * usableWidth
                            val py = size.height - paddingPx - point.y.toFloat() * usableHeight
                            hypot(down.position.x - px, down.position.y - py)
                        }
                        val nearestPoint = workingCurve.points[nearestIndex]
                        val nearestX = paddingPx + nearestPoint.x.toFloat() * usableWidth
                        val nearestY = size.height - paddingPx - nearestPoint.y.toFloat() * usableHeight
                        if (hypot(down.position.x - nearestX, down.position.y - nearestY) > hitRadiusPx) {
                            return@awaitEachGesture
                        }
                        selectedIndex = nearestIndex
                        val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture

                        dragging = true
                        try {
                            fun updatePoint(position: Offset) {
                                val usableWidth = (size.width - 2f * paddingPx).coerceAtLeast(1f)
                                val usableHeight = (size.height - 2f * paddingPx).coerceAtLeast(1f)
                                val normalizedX = ((position.x - paddingPx) / usableWidth)
                                    .coerceIn(0f, 1f)
                                    .toDouble()
                                val normalizedY = ((size.height - paddingPx - position.y) /
                                    usableHeight).coerceIn(0f, 1f).toDouble()
                                workingCurve = workingCurve.movePoint(
                                    selectedIndex,
                                    normalizedX,
                                    normalizedY,
                                )
                                onCurveChanged(workingCurve)
                            }

                            updatePoint(dragStart.position)
                            drag(dragStart.id) { change ->
                                change.consume()
                                updatePoint(change.position)
                            }
                        } finally {
                            dragging = false
                            onCurveChangeFinished()
                        }
                    }
                },
        ) {
            val left = paddingPx
            val right = size.width - paddingPx
            val top = paddingPx
            val bottom = size.height - paddingPx
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)

            fun plotPoint(x: Double, y: Double): Offset = Offset(
                x = left + x.toFloat() * width,
                y = bottom - y.toFloat() * height,
            )

            repeat(5) { gridIndex ->
                val fraction = gridIndex / 4f
                val gridColor = Color.White.copy(alpha = if (gridIndex == 0) 0.24f else 0.08f)
                drawLine(gridColor, Offset(left, top + height * fraction), Offset(right, top + height * fraction))
                drawLine(gridColor, Offset(left + width * fraction, top), Offset(left + width * fraction, bottom))
            }

            if (snapshot != null) {
                val stairPath = Path()
                for (sample in 0..180) {
                    val x = sample / 180.0
                    val requested = workingCurve.evaluate(x)
                    val quantized = VolumeQuantizer.quantize(
                        requested,
                        snapshot.range,
                        quantizationMode,
                    )
                    val point = plotPoint(x, quantized.effectiveNormalized)
                    if (sample == 0) stairPath.moveTo(point.x, point.y)
                    else stairPath.lineTo(point.x, point.y)
                }
                drawPath(
                    stairPath,
                    color = Color(0xFFA7F3D0).copy(alpha = 0.48f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            val curvePath = Path()
            for (sample in 0..180) {
                val x = sample / 180.0
                val point = plotPoint(x, workingCurve.evaluate(x))
                if (sample == 0) curvePath.moveTo(point.x, point.y)
                else curvePath.lineTo(point.x, point.y)
            }
            drawPath(
                curvePath,
                color = Color(0xFF7DD3FC),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )

            currentLogicalPosition?.let { logical ->
                val x = plotPoint(logical.coerceIn(0.0, 1.0), 0.0).x
                drawLine(
                    color = Color(0xFFFDE68A),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }

            workingCurve.points.forEachIndexed { index, point ->
                val position = plotPoint(point.x, point.y)
                drawCircle(
                    color = if (index == selectedIndex) Color(0xFFFDE68A) else Color(0xFFE0F2FE),
                    radius = if (index == selectedIndex) 9.dp.toPx() else 7.dp.toPx(),
                    center = position,
                )
                drawCircle(
                    color = Color(0xFF0B0F14),
                    radius = if (index == selectedIndex) 4.dp.toPx() else 3.dp.toPx(),
                    center = position,
                )
            }
        }

        Text(
            text = "选择控制点",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CurveEditorTestTags.POINT_SELECTOR),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(workingCurve.points) { index, controlPoint ->
                FilterChip(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    label = { Text((index + 1).toString()) },
                    modifier = Modifier
                        .testTag(CurveEditorTestTags.controlPoint(index))
                        .semantics {
                            contentDescription =
                                "选择控制点 ${index + 1}，x=${controlPoint.x.format2()}，" +
                                "V=${(controlPoint.y * 100).format1()}%"
                        },
                )
            }
        }

        val point = workingCurve.points[selectedIndex]
        Text(
            text = "控制点 ${selectedIndex + 1}：x=${point.x.format2()}，V=${(point.y * 100).format1()}%",
            modifier = Modifier
                .padding(top = 14.dp)
                .testTag(CurveEditorTestTags.SELECTED_POINT_VALUE),
            style = MaterialTheme.typography.titleSmall,
        )
        val xMinimum = if (selectedIndex == 0) 0.0 else {
            workingCurve.points[selectedIndex - 1].x + workingCurve.minimumXSpacing
        }
        val xMaximum = if (selectedIndex == workingCurve.points.lastIndex) 1.0 else {
            workingCurve.points[selectedIndex + 1].x - workingCurve.minimumXSpacing
        }
        val canMoveX = selectedIndex != 0 &&
            selectedIndex != workingCurve.points.lastIndex &&
            xMaximum - xMinimum > 1e-9
        Text(
            text = "逻辑按键位置 x",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = point.x.toFloat(),
            onValueChange = { value ->
                workingCurve = workingCurve.movePoint(selectedIndex, value.toDouble(), point.y)
                onCurveChanged(workingCurve)
            },
            enabled = canMoveX,
            valueRange = if (canMoveX) xMinimum.toFloat()..xMaximum.toFloat() else 0f..1f,
            onValueChangeFinished = onCurveChangeFinished,
            modifier = Modifier
                .testTag(CurveEditorTestTags.X_SLIDER)
                .semantics {
                    contentDescription = "控制点 ${selectedIndex + 1} 的逻辑位置 x"
                },
        )
        val yMinimum = if (selectedIndex == 0) 0.0 else workingCurve.points[selectedIndex - 1].y
        val yMaximum = if (selectedIndex == workingCurve.points.lastIndex) 1.0 else {
            workingCurve.points[selectedIndex + 1].y
        }
        val canMoveY = yMaximum - yMinimum > 1e-9
        Text("目标媒体音量 V", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = point.y.toFloat(),
            onValueChange = { value ->
                workingCurve = workingCurve.movePoint(selectedIndex, point.x, value.toDouble())
                onCurveChanged(workingCurve)
            },
            enabled = canMoveY,
            valueRange = if (canMoveY) yMinimum.toFloat()..yMaximum.toFloat() else 0f..1f,
            onValueChangeFinished = onCurveChangeFinished,
            modifier = Modifier
                .testTag(CurveEditorTestTags.Y_SLIDER)
                .semantics {
                    contentDescription = "控制点 ${selectedIndex + 1} 的目标媒体音量 V"
                },
        )
    }
}

private fun Double.format1(): String = String.format(Locale.ROOT, "%.1f", this)
private fun Double.format2(): String = String.format(Locale.ROOT, "%.2f", this)

object CurveEditorTestTags {
    const val CANVAS = "mapping_curve_canvas"
    const val POINT_SELECTOR = "mapping_curve_point_selector"
    const val SELECTED_POINT_VALUE = "mapping_curve_selected_point_value"
    const val X_SLIDER = "mapping_curve_x_slider"
    const val Y_SLIDER = "mapping_curve_y_slider"

    fun controlPoint(index: Int): String = "mapping_curve_control_point_$index"
}
