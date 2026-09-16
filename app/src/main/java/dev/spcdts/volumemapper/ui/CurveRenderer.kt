package dev.spcdts.volumemapper.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spcdts.volumemapper.core.StepVolumeMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 单帧不可变绘制数据；不持有手势、仓库或服务状态。 */
internal data class CurveRenderModel(
    val renderedMap: StepVolumeMap,
    val displayedControlIndices: List<Int>,
    val selectedIndex: Int,
    val selectedSegmentIndex: Int?,
    val selectedDisplayIndex: Int,
    val displayMinimum: Int,
    val displayMaximum: Int,
    val displaySpan: Int,
    val displayDenominator: Int,
    val currentDisplayIndex: Double?,
    val currentX: Double?,
    val currentBadgeText: String?,
    val leftPaddingPx: Float,
    val rightPaddingPx: Float,
    val topPaddingPx: Float,
    val bottomPaddingPx: Float,
    val tick: Color,
    val grid: Color,
    val curve: Color,
    val current: Color,
    val currentContent: Color,
    val selection: Color,
    val surface: Color,
)

internal fun DrawScope.drawMappingCurve(model: CurveRenderModel) = with(model) {
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
