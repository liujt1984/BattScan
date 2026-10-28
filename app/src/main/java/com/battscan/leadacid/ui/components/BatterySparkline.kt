package com.battscan.leadacid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.battscan.leadacid.monitor.ChartPoint
import kotlin.math.max

/**
 * Two-series sparkline used on the dashboard and the replay screen.
 *
 * Each series gets its own vertical scale: on a 72 V pack a 10 A current trace and a 76 V voltage
 * trace differ by nearly an order of magnitude in raw units, and sharing one axis flattens the
 * current line into noise.
 */
@Composable
fun BatterySparkline(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    showVoltage: Boolean = true,
    showCurrent: Boolean = true,
    voltageColor: Color = Color(0xFF3DDC84),
    currentColor: Color = Color(0xFFFFC14D),
) {
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (points.size < 2) {
            drawHint("等待数据")
            return@Canvas
        }
        val left = 8f
        val right = size.width - 8f
        val top = 8f
        val bottom = size.height - 8f
        val spanX = max(points.last().elapsedMs - points.first().elapsedMs, 1L).toDouble()
        fun xOf(elapsedMs: Long): Float =
            left + ((elapsedMs - points.first().elapsedMs) / spanX).toFloat() * (right - left)

        drawZeroLine(left, right, top, bottom)

        if (showVoltage) {
            val values = points.map { it.voltageV }
            val minV = values.min()
            val maxV = values.max()
            drawSeries(points.map { xOf(it.elapsedMs) to it.voltageV }, minV, maxV, voltageColor, top, bottom)
        }
        if (showCurrent) {
            val values = points.map { it.currentA }
            val minC = minOf(values.min(), 0.0)
            val maxC = maxOf(values.max(), 0.0)
            drawSeries(points.map { xOf(it.elapsedMs) to it.currentA }, minC, maxC, currentColor, top, bottom)
        }
    }
}

private fun DrawScope.drawHint(text: String) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.LTGRAY
        textSize = 32f
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2 - 60f, size.height / 2, paint)
}

private fun DrawScope.drawZeroLine(from: Float, to: Float, top: Float, bottom: Float) {
    drawLine(
        color = Color(0x40FFFFFF),
        start = Offset(from, (top + bottom) / 2f),
        end = Offset(to, (top + bottom) / 2f),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
    )
}

private fun DrawScope.drawSeries(
    samples: List<Pair<Float, Double>>,
    minValue: Double,
    maxValue: Double,
    color: Color,
    top: Float,
    bottom: Float,
) {
    val range = (maxValue - minValue).takeIf { it > 1e-6 } ?: 1.0
    val path = Path()
    samples.forEachIndexed { index, (x, value) ->
        val y = (bottom - ((value - minValue) / range).toFloat() * (bottom - top)).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
}

/** Formats a compact axis label pair for the caller to render beside a sparkline. */
fun voltageBounds(points: List<ChartPoint>): Pair<Double, Double> {
    if (points.isEmpty()) return 0.0 to 0.0
    return points.minOf { it.voltageV } to points.maxOf { it.voltageV }
}
