package com.locogo.astockguard.ui.chart

import android.content.res.AssetManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** 精确映射 APK 内置脚本；未知子资源直接拒绝，绝不回退到外网。 */
internal class ChartAssetClient(private val assets: AssetManager) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        // 部分 WebView 会把 loadDataWithBaseURL 的内部主文档也交给拦截器。
        // 交回 WebView 读取已注入的 HTML；网络仍由 blockNetworkLoads 禁用。
        if (request.isForMainFrame && request.method == "GET" &&
            (request.url.scheme == "data" || request.url.toString() == PAGE_URL)
        ) return null
        if (request.method == "GET" && request.url.toString() == SCRIPT_URL) {
            return runCatching { WebResourceResponse("application/javascript", "UTF-8", assets.open(SCRIPT_PATH)) }
                .getOrElse { missing() }
        }
        return missing()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true

    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val PAGE_URL = "https://appassets.androidplatform.net/"
        const val SCRIPT_PATH = "vendor/klinecharts-10.0.2/klinecharts.min.js"
        const val SCRIPT_URL = "https://appassets.androidplatform.net/$SCRIPT_PATH"
    }
}
