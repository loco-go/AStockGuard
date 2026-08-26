package com.locogo.astockguard.ui.chart

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.StockKLine

class KLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val webView = WebView(context)
    private var renderedKey: Int? = null

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = WebViewClient()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun render(period: ChartPeriod, candles: List<StockKLine>, minutes: List<MinuteBar>) {
        val key = 31 * period.hashCode() + if (period == ChartPeriod.MINUTE) minutes.hashCode() else candles.hashCode()
        if (renderedKey == key) return
        renderedKey = key
        val html = if (period == ChartPeriod.MINUTE) KLineHtmlBuilder.buildMinute(minutes) else KLineHtmlBuilder.buildCandles(candles)
        webView.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
    }

    fun setBars(bars: List<DailyBar>) {
        render(ChartPeriod.DAY, ChartDataMapper.mapDaily(bars), emptyList())
    }

    fun release() {
        renderedKey = null
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
        removeAllViews()
    }
}
