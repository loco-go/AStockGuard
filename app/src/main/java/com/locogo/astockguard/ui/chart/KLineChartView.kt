package com.locogo.astockguard.ui.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import com.locogo.astockguard.DailyBar

@Composable
fun KLineChartView(
    bars: List<DailyBar>,
    modifier: Modifier = Modifier
) {
    val html = remember(bars) {
        KLineHtmlBuilder.build(bars)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = {
            it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}
