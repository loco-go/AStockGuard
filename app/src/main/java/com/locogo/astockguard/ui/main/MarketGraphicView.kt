package com.locogo.astockguard.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.designsystem.R as DesignR
import kotlin.math.cos
import kotlin.math.sin

/** 参考设计的轻量原生图表；只绘制传入的真实样本，空数据保留明确空态。 */
class MarketGraphicView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = "line"
    private var values = emptyList<Double>()
    private var comparison = emptyList<Double>()
    private var labels = emptyList<String>()
    private var score: Double? = null
    private var center = "--"
    private var caption = ""
    private var emptyLabel = "暂无走势数据"
    private var axes = false
    private var rising = true
    private var candleValues = emptyList<DailyBar>()
    private val red get() = color(DesignR.color.gh_red)
    private val green get() = color(DesignR.color.gh_green)
    private val density get() = resources.displayMetrics.density

    /** 接入时间顺序明确的折线样本；不足两个点时不伪造走势。 */
    fun line(points: List<Double>, axisLabels: List<String> = emptyList(), showAxes: Boolean = false, second: List<Double> = emptyList(), empty: String = "暂无走势数据", positive: Boolean = true) {
        mode = "line"
        values = if (points.all { it.isFinite() }) points else emptyList()
        comparison = if (second.all { it.isFinite() }) second else emptyList()
        labels = axisLabels
        axes = showAxes
        rising = positive
        emptyLabel = empty
        contentDescription = if (values.size < 2) empty else "走势：${values.first()} 至 ${values.last()}，${values.size} 个样本"
        invalidate()
    }

    /** 策略卡中的小 K 线复用真实日线，不用示意折线替代蜡烛和成交量。 */
    fun candles(bars: List<DailyBar>) {
        mode = "candles"
        candleValues = bars.takeLast(24)
        contentDescription = if (bars.isEmpty()) "暂无日线数据" else "日 K 与成交量，共 ${candleValues.size} 个交易日"
        invalidate()
    }

    /** 展示明确口径的百分比环图或半圆仪表，缺失分值不绘制指针。 */
    fun gauge(value: Double?, title: String, subtitle: String, ring: Boolean = false) {
        mode = if (ring) "ring" else "gauge"
        score = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
        center = title
        caption = subtitle
        contentDescription = "$title，$subtitle"
        invalidate()
    }

    /** 展示并列涨跌柱状图；缺少历史统计时仅保留网格和空态。 */
    fun bars(up: List<Double>, down: List<Double>, dates: List<String>, empty: String) {
        mode = "bars"
        values = up
        comparison = down
        labels = dates
        emptyLabel = empty
        axes = true
        contentDescription = if (up.isEmpty()) empty else "涨跌家数历史，共 ${up.size} 个交易日"
        invalidate()
    }

    /** 根据图表模式绘制统一的参考图视觉样式。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        paint.shader = null
        when (mode) {
            "gauge", "ring" -> drawGauge(canvas)
            "bars" -> drawBars(canvas)
            "candles" -> drawCandles(canvas)
            else -> drawLine(canvas)
        }
    }

    /** 绘制从绿到红的分段仪表或红色仓位环，并保留中心数值层级。 */
    private fun drawGauge(canvas: Canvas) {
        val ring = mode == "ring"
        val small = width < dp(110f)
        val inset = dp(if (ring || small) 10f else 18f)
        val radius = minOf((width - inset * 2) / 2f, if (ring) (height - inset * 2) / 2f else (height - dp(42f)))
        val cx = width / 2f
        val cy = if (ring) height / 2f else radius + dp(14f)
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (ring) 8f else 10f)
        paint.strokeCap = if (ring) Paint.Cap.ROUND else Paint.Cap.BUTT
        paint.color = color(DesignR.color.gh_line)
        canvas.drawArc(rect, if (ring) -90f else 180f, if (ring) 360f else 180f, false, paint)
        if (ring) {
            score?.let {
                paint.color = red
                canvas.drawArc(rect, -90f, it.toFloat() * 3.6f, false, paint)
            }
        } else {
            val palette = intArrayOf(green, 0xFF75B565.toInt(), 0xFFD6B774.toInt(), 0xFFF39A63.toInt(), 0xFFF7745B.toInt(), red)
            palette.forEachIndexed { index, tint ->
                paint.color = tint
                canvas.drawArc(rect, 180f + index * 30f, 28.5f, false, paint)
            }
            score?.let {
                val angle = Math.toRadians(180 + it * 1.8)
                paint.color = red
                paint.strokeWidth = dp(3f)
                canvas.drawLine(cx + (cos(angle) * radius * 0.72).toFloat(), cy + (sin(angle) * radius * 0.72).toFloat(), cx + (cos(angle) * radius * 0.94).toFloat(), cy + (sin(angle) * radius * 0.94).toFloat(), paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        val valueY = if (ring || small) cy + dp(3f) else cy - radius * 0.17f
        drawText(canvas, center, cx, valueY, if (small) 15f else if (ring) 26f else 26f, if (ring) color(DesignR.color.gh_text) else red, true)
        drawText(canvas, caption, cx, valueY + dp(18f), 11f, color(DesignR.color.gh_secondary))
    }

    /** 在真实样本边界内绘制折线和渐隐填充，可选附带第二条基准曲线。 */
    private fun drawLine(canvas: Canvas) {
        val area = plotArea()
        if (axes) grid(canvas, area)
        if (values.size < 2) {
            drawText(canvas, emptyLabel, width / 2f, height / 2f + dp(3f), 10f, color(DesignR.color.gh_secondary))
            return
        }
        val all = values + comparison
        val low = all.minOrNull() ?: 0.0
        val high = all.maxOrNull() ?: 1.0
        val spread = (high - low).takeIf { it > 0 } ?: maxOf(kotlin.math.abs(high) * 0.01, 0.01)
        val path = curvePath(values, area, low, spread)
        val fill = Path(path).apply {
            lineTo(area.right, area.bottom)
            lineTo(area.left, area.bottom)
            close()
        }
        paint.style = Paint.Style.FILL
        val lineColor = if (rising) red else green
        paint.shader = LinearGradient(0f, area.top, 0f, area.bottom, withAlpha(lineColor, 48), withAlpha(lineColor, 0), Shader.TileMode.CLAMP)
        canvas.drawPath(fill, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = lineColor
        canvas.drawPath(path, paint)
        if (comparison.size > 1) {
            paint.color = color(DesignR.color.gh_blue)
            canvas.drawPath(curvePath(comparison, area, low, spread), paint)
        }
        if (axes) {
            drawText(canvas, format(high), dp(3f), area.top + dp(4f), 9f, color(DesignR.color.gh_secondary), align = Paint.Align.LEFT)
            drawText(canvas, format(low), dp(3f), area.bottom, 9f, color(DesignR.color.gh_secondary), align = Paint.Align.LEFT)
        }
    }

    /** 将 OHLC 和成交量分成上下两个绘图区，红涨绿跌并保留影线。 */
    private fun drawCandles(canvas: Canvas) {
        if (candleValues.isEmpty()) {
            drawText(canvas, "暂无日线数据", width / 2f, height / 2f, 10f, color(DesignR.color.gh_secondary))
            return
        }
        val min = candleValues.minOf { it.low }
        val max = candleValues.maxOf { it.high }
        val spread = (max - min).coerceAtLeast(0.01)
        val maxVolume = candleValues.maxOf { it.volume }.coerceAtLeast(1.0)
        val top = dp(8f)
        val chartHeight = height * 0.68f
        val step = (width - dp(8f)) / candleValues.size
        paint.strokeWidth = dp(0.8f)
        candleValues.forEachIndexed { index, bar ->
            val x = dp(4f) + step * (index + 0.5f)
            val yHigh = top + ((max - bar.high) / spread * chartHeight).toFloat()
            val yLow = top + ((max - bar.low) / spread * chartHeight).toFloat()
            val yOpen = top + ((max - bar.open) / spread * chartHeight).toFloat()
            val yClose = top + ((max - bar.close) / spread * chartHeight).toFloat()
            paint.color = if (bar.close >= bar.open) red else green
            paint.style = Paint.Style.STROKE
            canvas.drawLine(x, yHigh, x, yLow, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRect(x - step * 0.3f, minOf(yOpen, yClose), x + step * 0.3f, maxOf(yOpen, yClose) + dp(0.8f), paint)
            val volumeTop = height - dp(3f) - (bar.volume / maxVolume * height * 0.17f).toFloat()
            canvas.drawRect(x - step * 0.3f, volumeTop, x + step * 0.3f, height - dp(3f), paint)
        }
    }

    /** 计算线性坐标，不对缺失分钟或缺失交易日插入虚构样本。 */
    private fun curvePath(points: List<Double>, area: RectF, low: Double, spread: Double): Path = Path().apply {
        points.forEachIndexed { index, value ->
            val x = area.left + area.width() * index / (points.size - 1)
            val y = area.bottom - ((value - low) / spread * area.height()).toFloat()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    /** 绘制两组历史家数，柱高按样本最大值统一归一。 */
    private fun drawBars(canvas: Canvas) {
        val area = plotArea()
        grid(canvas, area)
        if (values.isEmpty()) {
            drawText(canvas, emptyLabel, width / 2f, height / 2f, 11f, color(DesignR.color.gh_secondary))
            return
        }
        val max = (values + comparison).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val step = area.width() / values.size
        paint.style = Paint.Style.FILL
        values.forEachIndexed { index, value ->
            val x = area.left + step * index + step * 0.17f
            paint.color = red
            canvas.drawRect(x, area.bottom - (value / max * area.height()).toFloat(), x + step * 0.27f, area.bottom, paint)
            comparison.getOrNull(index)?.let {
                paint.color = green
                canvas.drawRect(x + step * 0.32f, area.bottom - (it / max * area.height()).toFloat(), x + step * 0.59f, area.bottom, paint)
            }
        }
    }

    /** 为带坐标轴的图形预留边距，小趋势图则贴合卡片内边界。 */
    private fun plotArea() = if (axes) RectF(dp(30f), dp(12f), width - dp(6f), height - dp(24f)) else RectF(dp(2f), dp(5f), width - dp(2f), height - dp(4f))

    /** 绘制浅色水平网格和首尾日期，保持参考图的低对比辅助信息。 */
    private fun grid(canvas: Canvas, area: RectF) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.6f)
        paint.color = color(DesignR.color.gh_line)
        repeat(5) { i ->
            val y = area.top + i * area.height() / 4
            canvas.drawLine(area.left, y, area.right, y, paint)
        }
        if (labels.isNotEmpty()) {
            drawText(canvas, labels.first(), area.left, height - dp(6f), 9f, color(DesignR.color.gh_secondary), align = Paint.Align.LEFT)
            drawText(canvas, labels.last(), area.right, height - dp(6f), 9f, color(DesignR.color.gh_secondary), align = Paint.Align.RIGHT)
        }
    }

    /** 集中绘制图内文字，避免不同图表出现字号和颜色漂移。 */
    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, tint: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = tint
        paint.textSize = size * resources.displayMetrics.scaledDensity
        paint.isFakeBoldText = bold
        paint.textAlign = align
        canvas.drawText(text, x, y, paint)
        paint.isFakeBoldText = false
    }

    /** 将资源色转换为当前深浅色主题的颜色值。 */
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    /** 将设计尺寸转换为像素。 */
    private fun dp(value: Float) = value * density

    /** 仅改变绘图颜色透明度，不更改涨跌语义。 */
    private fun withAlpha(tint: Int, alpha: Int) = Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint))

    /** 坐标轴采用短格式，避免窄屏数字相互覆盖。 */
    private fun format(value: Double) = if (kotlin.math.abs(value) >= 1000) String.format(java.util.Locale.CHINA, "%.0f", value) else String.format(java.util.Locale.CHINA, "%.1f", value)
}
