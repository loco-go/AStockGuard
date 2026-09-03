package com.locogo.astockguard.data.news

/*
 * 文件职责：读取并解析 HTTPS RSS/Atom 新闻源；限制响应规模并容忍单条脏数据，不把网络错误当成无风险。
 * 架构边界：解析失败、超时和字段缺失要显式返回失败或不可用状态，不能用零值伪造有效行情。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class NewsFeedClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch(source: NewsSource): List<NewsItem> = withContext(Dispatchers.IO) {
        require(source.url.startsWith("https://")) { "新闻源仅允许 HTTPS: ${source.name}" }
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "AStockGuard/3.1 Android RSS Reader")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("${source.name} HTTP ${response.code}")
            val xml = response.body?.string().orEmpty()
            if (xml.isBlank()) return@withContext emptyList()
            parse(source, xml)
        }
    }

    private fun parse(source: NewsSource, xml: String): List<NewsItem> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val result = mutableListOf<NewsItem>()
        var event = parser.eventType
        var inEntry = false
        var title = ""
        var link = ""
        var guid = ""
        var published = ""
        val fetchedAt = System.currentTimeMillis()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                    if (tag == "item" || tag == "entry") {
                        inEntry = true; title = ""; link = ""; guid = ""; published = ""
                    } else if (inEntry) {
                        when (tag) {
                            "title" -> title = safeText(parser)
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: safeText(parser)
                            }
                            "guid", "id" -> guid = safeText(parser)
                            "pubdate", "published", "updated" -> published = safeText(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                    if (tag == "item" || tag == "entry") {
                        if (title.isNotBlank()) {
                            val id = (guid.ifBlank { link }.ifBlank { title }).trim()
                            result += NewsItem(
                                id = id,
                                source = source.name,
                                title = title.trim(),
                                url = link.trim(),
                                publishedAt = parseDate(published),
                                fetchedAt = fetchedAt
                            )
                        }
                        inEntry = false
                    }
                }
            }
            event = parser.next()
        }
        return result.take(80)
    }

    private fun safeText(parser: XmlPullParser): String = runCatching { parser.nextText() }.getOrDefault("")

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        patterns.forEach { pattern ->
            val value = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)?.time
            }.getOrNull()
            if (value != null) return value
        }
        return 0L
    }
}
