package com.locogo.astockguard.ui.chart

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.locogo.astockguard.DailyBar

/** XML-compatible shell for the existing KLineCharts HTML implementation. */
class KLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val webView = WebView(context)
    private var renderedKey: Int? = null

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setBars(bars: List<DailyBar>) {
        val key = bars.hashCode()
        if (renderedKey == key) return
        renderedKey = key
        webView.loadDataWithBaseURL(null, KLineHtmlBuilder.build(bars), "text/html", "UTF-8", null)
    }

    fun release() {
        renderedKey = null
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
        removeAllViews()
    }
}
