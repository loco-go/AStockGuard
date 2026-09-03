package com.locogo.astockguard

/*
 * 文件职责：封装 API AI Provider、认证和结构化请求；密钥不写日志，AI 失败不能影响本地行情监控。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class AiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(primary: AiProviderConfig, backup: AiProviderConfig?, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            callProvider(primary, prompt)
        } catch (e: Throwable) {
            if (backup != null && backup.baseUrl.isNotBlank() && backup.apiKey.isNotBlank()) {
                runCatching { callProvider(backup, prompt) }
                    .getOrElse { backupError ->
                        throw IOException("主接口失败：${friendlyMessage(e)}；备用接口也失败：${friendlyMessage(backupError)}", backupError)
                    }
            } else {
                throw IOException(friendlyMessage(e), e)
            }
        }
    }

    private fun callProvider(config: AiProviderConfig, prompt: String): String = when (config.type.uppercase()) {
        "CHATGPT_WEB" -> throw IOException("CHATGPT_WEB 现已改为 Android WebView 正常登录模式，请从界面打开 WebView；不再用 OkHttp 直连私有网页会话接口。")
        "CHAT_COMPLETIONS" -> callChatCompletions(config, prompt)
        "LOCAL" -> "LOCAL 模式：AI未联网。请依据本地 E/M/R2 信号操作。"
        else -> callResponses(config, prompt)
    }

    private fun callResponses(config: AiProviderConfig, prompt: String): String {
        require(config.baseUrl.isNotBlank()) { "请配置 AI Base URL" }
        require(config.apiKey.isNotBlank()) { "请配置 API Key / Token" }
        val url = config.baseUrl.trimEnd('/') + "/responses"
        val body = JSONObject()
            .put("model", config.model.ifBlank { "gpt-5.6" })
            .put("instructions", SYSTEM_INSTRUCTIONS)
            .put("input", prompt)
        val request = baseBuilder(config, url)
            .post(body.toString().toRequestBody(jsonType)).build()
        return execute(request) { parseResponses(it) }
    }

    private fun callChatCompletions(config: AiProviderConfig, prompt: String): String {
        require(config.baseUrl.isNotBlank()) { "请配置 AI Base URL" }
        require(config.apiKey.isNotBlank()) { "请配置 API Key / Token" }
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_INSTRUCTIONS))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", config.model.ifBlank { "deepseek-chat" })
            .put("messages", messages)
            .put("stream", false)
        val request = baseBuilder(config, url)
            .post(body.toString().toRequestBody(jsonType)).build()
        return execute(request) { text ->
            JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty().ifBlank { text }
        }
    }

    private fun baseBuilder(config: AiProviderConfig, url: String): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Client-Request-Id", UUID.randomUUID().toString())
        applyExtraHeaders(b, config.extraHeadersJson)
        return b
    }

    private fun applyExtraHeaders(builder: Request.Builder, json: String) {
        runCatching {
            val headers = JSONObject(json.ifBlank { "{}" })
            headers.keys().forEach { key ->
                if (!key.equals("Authorization", true) && !key.equals("Cookie", true)) {
                    builder.header(key, headers.optString(key))
                }
            }
        }
    }

    private fun execute(request: Request, parser: (String) -> String): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 408 || response.code == 429 || response.code >= 500) {
                throw RetryableProviderException("HTTP ${response.code}: ${text.take(300)}")
            }
            if (response.code == 403) {
                val lower = text.lowercase()
                if ("unusual activity" in lower || "detected from your device" in lower || "device" in lower) {
                    throw WebSessionRejectedException(
                        "HTTP 403：网页版会话被服务端设备/会话风控拒绝（Unusual activity / detected from your device）。" +
                            "这不是 JSON 长度问题；更换 User-Agent 或伪造设备信息并不能可靠解决。请先在官方 ChatGPT App/浏览器确认该账号能正常使用，或配置备用 AI Provider。"
                    )
                }
                throw WebSessionRejectedException("HTTP 403：服务端拒绝当前会话/客户端。${text.take(220)}")
            }
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(500)}")
            return parser(text).ifBlank { text }
        }
    }

    private fun parseResponses(text: String): String {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return text
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: return text
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") c.optString("text").takeIf(String::isNotBlank)?.let(parts::add)
            }
        }
        return parts.joinToString("\n")
    }

    private fun parseSseConversation(text: String): String {
        var last = ""
        text.lineSequence().filter { it.startsWith("data:") }.forEach { line ->
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]" || data.isBlank()) return@forEach
            runCatching {
                val root = JSONObject(data)
                val parts = root.optJSONObject("message")
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val joined = if (parts != null) (0 until parts.length()).joinToString("\n") { parts.optString(it) } else ""
                if (joined.isNotBlank()) last = joined
            }
        }
        return last.ifBlank { text }
    }

    private fun friendlyMessage(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    private class RetryableProviderException(message: String) : IOException(message)
    private class WebSessionRejectedException(message: String) : IOException(message)

    companion object {
        private const val SYSTEM_INSTRUCTIONS = "你是A股盘中风险与仓位助手。只依据输入的行情快照判断，不得编造未提供的实时数据。优先输出：E风险、M周期、建议总仓位、持仓强弱、最需处理的一件事、升仓和降仓触发条件。不要自动下单。"
    }
}
