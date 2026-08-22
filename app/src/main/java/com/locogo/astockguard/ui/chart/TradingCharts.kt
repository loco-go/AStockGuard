package com.locogo.astockguard.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import kotlin.math.max
import kotlin.math.min

data class ChartSignal(val index: Int, val label: String)

@Composable
fun MinuteChart(bars: List<MinuteBar>, modifier: Modifier = Modifier) {
    if (bars.size < 2) { Text("暂无分时数据"); return }
    val priceMin = bars.minOf { min(it.price, it.avgPrice) }
    val priceMax = bars.maxOf { max(it.price, it.avgPrice) }
    val range = (priceMax - priceMin).takeIf { it > 0 } ?: 1.0
    Canvas(modifier = modifier.height(210.dp).fillMaxWidth()) {
        fun y(v: Double) = size.height - ((v - priceMin) / range * size.height).toFloat()
        val step = size.width / (bars.size - 1)
        val pricePath = Path(); val avgPath = Path()
        bars.forEachIndexed { i, b ->
            val x = i * step
            if (i == 0) { pricePath.moveTo(x, y(b.price)); avgPath.moveTo(x, y(b.avgPrice)) }
            else { pricePath.lineTo(x, y(b.price)); avgPath.lineTo(x, y(b.avgPrice)) }
        }
        drawPath(pricePath, Color(0xFF4F7DFF), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        drawPath(avgPath, Color(0xFFF0A128), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
    }
}

@Composable
fun CandlestickChart(
    bars: List<DailyBar>,
    signals: List<ChartSignal> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (bars.isEmpty()) { Text("暂无K线数据"); return }
    var cursor by remember { mutableStateOf<Int?>(null) }
    val low = bars.minOf { it.low }
    val high = bars.maxOf { it.high }
    val range = (high - low).takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(
            modifier = modifier.height(250.dp).fillMaxWidth().pointerInput(bars) {
                detectDragGestures(
                    onDragStart = { p -> cursor = ((p.x / size.width) * bars.size).toInt().coerceIn(0, bars.lastIndex) },
                    onDrag = { change, _ -> cursor = ((change.position.x / size.width) * bars.size).toInt().coerceIn(0, bars.lastIndex) }
                )
            }
        ) {
            fun y(v: Double) = size.height - ((v - low) / range * size.height).toFloat()
            val slot = size.width / bars.size
            bars.forEachIndexed { index, b ->
                val x = slot * index + slot / 2
                val rising = b.close >= b.open
                val color = if (rising) Color(0xFFD84A4A) else Color(0xFF2CA66F)
                drawLine(color, Offset(x, y(b.high)), Offset(x, y(b.low)), strokeWidth = 2f)
                val top = y(max(b.open, b.close)); val bottom = y(min(b.open, b.close))
                drawRect(color, Offset(x - slot * .28f, top), androidx.compose.ui.geometry.Size(slot * .56f, max(2f, bottom - top)))
            }
            signals.forEach { signal ->
                if (signal.index in bars.indices) {
                    val x = slot * signal.index + slot / 2
                    drawCircle(Color(0xFF7B61FF), 7f, Offset(x, y(bars[signal.index].low) + 12f))
                }
            }
            cursor?.let { i ->
                val x = slot * i + slot / 2
                drawLine(Color.Gray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
        }
        cursor?.let { i ->
            val b = bars[i]
            Text("${b.date}  O ${b.open}  H ${b.high}  L ${b.low}  C ${b.close}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun EquityCurve(points: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    if (points.size < 2) { Text("暂无组合净值数据"); return }
    val minV = points.minOf { it.second }; val maxV = points.maxOf { it.second }
    val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
    Canvas(modifier.height(160.dp).fillMaxWidth()) {
        val step = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = i * step; val y = size.height - ((p.second - minV) / range * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF6B8AFD), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}
