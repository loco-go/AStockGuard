package com.locogo.astockguard.ui.chart

/*
 * 文件职责：封装图表 WebView 生命周期、数据注入与点击回调；页面脚本只负责绘图，不执行交易逻辑。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.strategy.IntradayChartSignal

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
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHorizontalScrollBarEnabled = false
        webView.webViewClient = WebViewClient()
        configureTouchInterop()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 横向手势交给K线图，纵向手势继续交给页面滚动容器。
     * 只有横向位移超过系统阈值后才禁止父容器拦截，可消除斜向拖动时的抖动。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun configureTouchInterop() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx > touchSlop) requestDisallowInterceptTouchEvent(dx > dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    fun render(
        period: ChartPeriod,
        candles: List<StockKLine>,
        minutes: List<MinuteCandle>,
        signals: List<ChartSignal> = emptyList(),
        minuteSignals: List<IntradayChartSignal> = emptyList()
    ) {
        val contentKey = if (period == ChartPeriod.MINUTE) minutes.hashCode() else candles.hashCode()
        val key = 31 * (31 * (31 * period.hashCode() + contentKey) + signals.hashCode()) + minuteSignals.hashCode()
        if (renderedKey == key) return
        renderedKey = key
        val html = if (period == ChartPeriod.MINUTE) {
            KLineHtmlBuilder.buildMinute(minutes, minuteSignals)
        } else {
            KLineHtmlBuilder.buildCandles(candles, signals, period)
        }
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
