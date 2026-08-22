package com.locogo.astockguard.ui.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.local.TradeRecordEntity
import com.locogo.astockguard.domain.trading.TTradePlan
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class TradingChartMode { MINUTE, DAILY }

private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val TRADE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(CHINA_ZONE)
private val TRADE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(CHINA_ZONE)

private class TradingChartBridge(private val onBuyAnchor: (Double) -> Unit) {
    @JavascriptInterface
    fun selectBuyAnchor(price: Double) {
        if (price.isFinite() && price > 0.0) onBuyAnchor(price)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EChartsTradingChart(
    dailyBars: List<DailyBar>,
    minuteBars: List<MinuteBar>,
    trades: List<TradeRecordEntity>,
    plan: TTradePlan?,
    mode: TradingChartMode,
    onBuyAnchor: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val minuteTradeDate = dailyBars.lastOrNull()?.date
    val effectiveTrades = remember(trades, mode, minuteTradeDate) {
        if (mode == TradingChartMode.MINUTE && !minuteTradeDate.isNullOrBlank()) {
            trades.filter { trade -> TRADE_DATE_FORMATTER.format(Instant.ofEpochMilli(trade.tradeAt)) == minuteTradeDate }
        } else {
            trades
        }
    }
    val html = remember(dailyBars, minuteBars, effectiveTrades, plan, mode, dark) {
        buildTradingChartHtml(dailyBars, minuteBars, effectiveTrades, plan, mode, dark)
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(430.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                addJavascriptInterface(TradingChartBridge { price -> post { onBuyAnchor(price) } }, "AStockBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                }
            }
        },
        update = { webView ->
            val contentKey = html.hashCode()
            if (webView.tag != contentKey) {
                webView.tag = contentKey
                webView.loadDataWithBaseURL(
                    "https://cdn.jsdelivr.net/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}

private fun buildTradingChartHtml(
    dailyBars: List<DailyBar>,
    minuteBars: List<MinuteBar>,
    trades: List<TradeRecordEntity>,
    plan: TTradePlan?,
    mode: TradingChartMode,
    dark: Boolean
): String {
    val background = if (dark) "#0A0F1C" else "#FFFFFF"
    val text = if (dark) "#D5D9E2" else "#2A2F3A"
    val grid = if (dark) "#20283A" else "#E7EAF0"
    val tooltipBg = if (dark) "#151D2E" else "#FFFFFF"
    val buyColor = "#E84C4C"
    val sellColor = "#16A085"
    val planColor = "#F5A623"
    val invalidColor = "#8A93A5"

    val option = if (mode == TradingChartMode.MINUTE && minuteBars.isNotEmpty()) {
        minuteOption(minuteBars, trades, plan, text, grid, tooltipBg, buyColor, sellColor, planColor, invalidColor)
    } else {
        dailyOption(dailyBars, trades, plan, text, grid, tooltipBg, buyColor, sellColor, planColor, invalidColor)
    }

    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
          <style>
            html,body,#chart { width:100%; height:100%; margin:0; padding:0; background:$background; overflow:hidden; }
          </style>
          <script src="https://cdn.jsdelivr.net/npm/echarts@6.1.0/dist/echarts.min.js"></script>
        </head>
        <body>
          <div id="chart"></div>
          <script>
            const chart = echarts.init(document.getElementById('chart'), null, {renderer:'canvas', useCoarsePointer:true, pointerSize:44});
            const option = $option;
            chart.setOption(option);
            chart.on('click', 'series', function(params) {
              let price = null;
              if (params.seriesType === 'candlestick' && Array.isArray(params.value)) {
                price = Number(params.value[1]);
              } else if (typeof params.value === 'number') {
                price = Number(params.value);
              } else if (Array.isArray(params.value) && params.value.length > 1) {
                price = Number(params.value[1]);
              }
              if (price && price > 0 && window.AStockBridge) window.AStockBridge.selectBuyAnchor(price);
            });
            window.addEventListener('resize', () => chart.resize());
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun minuteOption(
    bars: List<MinuteBar>,
    trades: List<TradeRecordEntity>,
    plan: TTradePlan?,
    text: String,
    gridColor: String,
    tooltipBg: String,
    buyColor: String,
    sellColor: String,
    planColor: String,
    invalidColor: String
): String {
    val x = JSONArray(bars.map { it.time })
    val price = JSONArray(bars.map { it.price })
    val avg = JSONArray(bars.map { it.avgPrice })
    val volume = JSONArray(bars.map { it.volume })
    val marks = tradeMarksMinute(bars, trades, buyColor, sellColor)
    val overlays = planOverlay(plan, planColor, invalidColor)

    return JSONObject().apply {
        put("animation", false)
        put("textStyle", JSONObject().put("color", text))
        put("tooltip", JSONObject().put("trigger", "axis").put("backgroundColor", tooltipBg).put("borderWidth", 0))
        put("axisPointer", JSONObject().put("link", JSONArray().put(JSONObject().put("xAxisIndex", "all"))))
        put("grid", JSONArray()
            .put(JSONObject().put("left", 48).put("right", 18).put("top", 20).put("height", "62%"))
            .put(JSONObject().put("left", 48).put("right", 18).put("top", "72%").put("height", "14%")))
        put("xAxis", JSONArray()
            .put(JSONObject().put("type", "category").put("data", x).put("boundaryGap", false).put("axisLine", axisLine(gridColor)).put("axisLabel", axisLabel(text)))
            .put(JSONObject().put("type", "category").put("gridIndex", 1).put("data", x).put("axisLabel", JSONObject().put("show", false)).put("axisLine", axisLine(gridColor))))
        put("yAxis", JSONArray()
            .put(JSONObject().put("scale", true).put("splitLine", splitLine(gridColor)).put("axisLabel", axisLabel(text)))
            .put(JSONObject().put("scale", true).put("gridIndex", 1).put("splitLine", JSONObject().put("show", false)).put("axisLabel", axisLabel(text))))
        put("dataZoom", JSONArray()
            .put(JSONObject().put("type", "inside").put("xAxisIndex", JSONArray(listOf(0, 1))).put("start", 45).put("end", 100))
            .put(JSONObject().put("type", "slider").put("xAxisIndex", JSONArray(listOf(0, 1))).put("height", 18).put("bottom", 4).put("start", 45).put("end", 100)))
        put("series", JSONArray()
            .put(JSONObject().put("name", "价格").put("type", "line").put("showSymbol", false).put("smooth", false).put("data", price)
                .put("lineStyle", JSONObject().put("width", 1.6).put("color", "#4C8DFF"))
                .put("markPoint", JSONObject().put("data", marks))
                .put("markArea", overlays.first)
                .put("markLine", overlays.second))
            .put(JSONObject().put("name", "均价").put("type", "line").put("showSymbol", false).put("data", avg)
                .put("lineStyle", JSONObject().put("width", 1.2).put("color", "#F1C75B")))
            .put(JSONObject().put("name", "成交量").put("type", "bar").put("xAxisIndex", 1).put("yAxisIndex", 1).put("data", volume)
                .put("itemStyle", JSONObject().put("color", "#526078"))))
    }.toString()
}

private fun dailyOption(
    bars: List<DailyBar>,
    trades: List<TradeRecordEntity>,
    plan: TTradePlan?,
    text: String,
    gridColor: String,
    tooltipBg: String,
    buyColor: String,
    sellColor: String,
    planColor: String,
    invalidColor: String
): String {
    val dates = JSONArray(bars.map { it.date })
    val kData = JSONArray(bars.map { JSONArray(listOf(it.open, it.close, it.low, it.high)) })
    val volume = JSONArray(bars.map { it.volume })
    val marks = tradeMarksDaily(bars, trades, buyColor, sellColor)
    val overlays = planOverlay(plan, planColor, invalidColor)

    return JSONObject().apply {
        put("animation", false)
        put("textStyle", JSONObject().put("color", text))
        put("tooltip", JSONObject().put("trigger", "axis").put("backgroundColor", tooltipBg).put("borderWidth", 0))
        put("axisPointer", JSONObject().put("link", JSONArray().put(JSONObject().put("xAxisIndex", "all"))))
        put("grid", JSONArray()
            .put(JSONObject().put("left", 48).put("right", 18).put("top", 20).put("height", "62%"))
            .put(JSONObject().put("left", 48).put("right", 18).put("top", "72%").put("height", "14%")))
        put("xAxis", JSONArray()
            .put(JSONObject().put("type", "category").put("data", dates).put("boundaryGap", true).put("axisLine", axisLine(gridColor)).put("axisLabel", axisLabel(text)))
            .put(JSONObject().put("type", "category").put("gridIndex", 1).put("data", dates).put("axisLabel", JSONObject().put("show", false)).put("axisLine", axisLine(gridColor))))
        put("yAxis", JSONArray()
            .put(JSONObject().put("scale", true).put("splitLine", splitLine(gridColor)).put("axisLabel", axisLabel(text)))
            .put(JSONObject().put("scale", true).put("gridIndex", 1).put("splitLine", JSONObject().put("show", false)).put("axisLabel", axisLabel(text))))
        put("dataZoom", JSONArray()
            .put(JSONObject().put("type", "inside").put("xAxisIndex", JSONArray(listOf(0, 1))).put("start", 20).put("end", 100))
            .put(JSONObject().put("type", "slider").put("xAxisIndex", JSONArray(listOf(0, 1))).put("height", 18).put("bottom", 4).put("start", 20).put("end", 100)))
        put("series", JSONArray()
            .put(JSONObject().put("name", "K线").put("type", "candlestick").put("data", kData)
                .put("itemStyle", JSONObject().put("color", "#E84C4C").put("color0", "#16A085").put("borderColor", "#E84C4C").put("borderColor0", "#16A085"))
                .put("markPoint", JSONObject().put("data", marks))
                .put("markArea", overlays.first)
                .put("markLine", overlays.second))
            .put(JSONObject().put("name", "成交量").put("type", "bar").put("xAxisIndex", 1).put("yAxisIndex", 1).put("data", volume)
                .put("itemStyle", JSONObject().put("color", "#526078"))))
    }.toString()
}

private fun tradeMarksDaily(
    bars: List<DailyBar>,
    trades: List<TradeRecordEntity>,
    buyColor: String,
    sellColor: String
): JSONArray {
    val dateSet = bars.map { it.date }.toHashSet()
    val result = JSONArray()
    trades.forEach { trade ->
        val date = TRADE_DATE_FORMATTER.format(Instant.ofEpochMilli(trade.tradeAt))
        if (date !in dateSet) return@forEach
        result.put(markPoint(date, trade.price, trade.side, trade.quantity, if (trade.side.uppercase() == "BUY") buyColor else sellColor))
    }
    return result
}

private fun tradeMarksMinute(
    bars: List<MinuteBar>,
    trades: List<TradeRecordEntity>,
    buyColor: String,
    sellColor: String
): JSONArray {
    if (bars.isEmpty()) return JSONArray()
    val times = bars.map { it.time }
    val result = JSONArray()
    trades.forEach { trade ->
        val time = TRADE_TIME_FORMATTER.format(Instant.ofEpochMilli(trade.tradeAt))
        val closest = times.minByOrNull { abs(minutes(it) - minutes(time)) } ?: return@forEach
        result.put(markPoint(closest, trade.price, trade.side, trade.quantity, if (trade.side.uppercase() == "BUY") buyColor else sellColor))
    }
    return result
}

private fun markPoint(x: String, price: Double, side: String, quantity: Int, color: String) = JSONObject()
    .put("name", side)
    .put("coord", JSONArray(listOf(x, price)))
    .put("value", "${if (side.uppercase() == "BUY") "买" else "卖"}$quantity")
    .put("symbol", if (side.uppercase() == "BUY") "pin" else "triangle")
    .put("symbolSize", 42)
    .put("itemStyle", JSONObject().put("color", color))
    .put("label", JSONObject().put("color", "#FFFFFF").put("fontSize", 9))

private fun planOverlay(plan: TTradePlan?, planColor: String, invalidColor: String): Pair<JSONObject, JSONObject> {
    val areas = JSONArray()
    val lines = JSONArray()
    if (plan != null && plan.buyZoneLow > 0 && plan.buyZoneHigh > 0) {
        areas.put(JSONArray()
            .put(JSONObject().put("name", "计划买入区").put("yAxis", plan.buyZoneLow).put("itemStyle", JSONObject().put("color", "rgba(245,166,35,0.10)")))
            .put(JSONObject().put("yAxis", plan.buyZoneHigh)))
    }
    if (plan != null && plan.sellZoneLow > 0 && plan.sellZoneHigh > 0) {
        areas.put(JSONArray()
            .put(JSONObject().put("name", "计划卖出区").put("yAxis", plan.sellZoneLow).put("itemStyle", JSONObject().put("color", "rgba(22,160,133,0.10)")))
            .put(JSONObject().put("yAxis", plan.sellZoneHigh)))
    }
    if (plan != null && plan.manualAnchorPrice != null) {
        lines.put(JSONObject().put("name", "手选买点").put("yAxis", plan.manualAnchorPrice)
            .put("lineStyle", JSONObject().put("color", planColor).put("type", "dashed")))
    }
    if (plan != null && plan.invalidPrice > 0) {
        lines.put(JSONObject().put("name", "失效位").put("yAxis", plan.invalidPrice)
            .put("lineStyle", JSONObject().put("color", invalidColor).put("type", "dashed")))
    }
    return JSONObject().put("silent", true).put("data", areas) to
        JSONObject().put("silent", true)
            .put("symbol", JSONArray(listOf("none", "none")))
            .put("label", JSONObject().put("show", true))
            .put("data", lines)
}

private fun axisLine(color: String) = JSONObject().put("lineStyle", JSONObject().put("color", color))
private fun axisLabel(color: String) = JSONObject().put("color", color).put("fontSize", 10)
private fun splitLine(color: String) = JSONObject().put("lineStyle", JSONObject().put("color", color).put("opacity", 0.55))

private fun minutes(value: String): Int {
    val trimmed = value.trim()
    if (':' in trimmed) {
        val p = trimmed.takeLast(5).split(':')
        if (p.size == 2) return (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
    }
    val digits = trimmed.filter(Char::isDigit)
    if (digits.length >= 4) {
        val hhmm = digits.takeLast(4)
        return (hhmm.take(2).toIntOrNull() ?: 0) * 60 + (hhmm.takeLast(2).toIntOrNull() ?: 0)
    }
    return 0
}
