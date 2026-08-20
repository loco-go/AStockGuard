package com.locogo.astockguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class AiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(primary: AiProviderConfig, backup: AiProviderConfig?, prompt: String): String = withContext(Dispatchers.IO) {
        require(primary.baseUrl.isNotBlank()) { "请配置 AI Base URL" }
        require(primary.apiKey.isNotBlank()) { "请配置 AI API Key" }
        try {
            callProvider(primary, prompt)
        } catch (e: RetryableProviderException) {
            if (backup != null) callProvider(backup, prompt)
            else throw e
        } catch (e: IOException) {
            if (backup != null) callProvider(backup, prompt)
            else throw e
        }
    }

    private fun callProvider(config: AiProviderConfig, prompt: String): String {
        val url = config.baseUrl.trimEnd('/') + "/responses"
        val bodyJson = JSONObject()
            .put("model", config.model.ifBlank { "gpt-5.6" })
            .put(
                "instructions",
                "你是A股盘中风险与仓位助手。只依据输入的行情快照判断，不得编造未提供的实时数据。输出：市场风险、建议仓位、持仓强弱、最需处理的一件事。不要自动下单。"
            )
            .put("input", prompt)

        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Client-Request-Id", UUID.randomUUID().toString())

        runCatching {
            val headers = JSONObject(config.extraHeadersJson.ifBlank { "{}" })
            headers.keys().forEach { key ->
                if (!key.equals("Authorization", ignoreCase = true)) {
                    builder.header(key, headers.optString(key))
                }
            }
        }

        val request = builder.post(bodyJson.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 408 || response.code == 429 || response.code >= 500) {
                throw RetryableProviderException("${config.name} HTTP ${response.code}: $text")
            }
            if (!response.isSuccessful) {
                error("${config.name} HTTP ${response.code}: $text")
            }
            return parseOutputText(text).ifBlank { text }
        }
    }

    private fun parseOutputText(text: String): String {
        val root = JSONObject(text)
        val output = root.optJSONArray("output") ?: return root.optString("output_text")
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") {
                    c.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                }
            }
        }
        return parts.joinToString("\n")
    }

    private class RetryableProviderException(message: String) : IOException(message)
}
