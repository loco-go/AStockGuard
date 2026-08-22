package com.locogo.astockguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * 只在本机解析用户粘贴/导入的配置文本。
 * 优先按 JSON 解析，失败后再使用宽松文本匹配。
 * 原始文本不会被持久化；只保存提取出的字段。
 */
object CredentialParser {
    private const val MAX_IMPORT_CHARS = 4_000_000

    fun parse(raw: String): AiProviderConfig {
        require(raw.isNotBlank()) { "输入内容为空" }
        require(raw.length <= MAX_IMPORT_CHARS) { "导入文本过大（>${MAX_IMPORT_CHARS / 1_000_000}MB 字符），请改用更精简的配置文件" }

        val text = stripCodeFence(raw)
        val jsonValues = parseJsonValues(text)

        fun findValue(vararg keys: String): String {
            keys.forEach { key ->
                jsonValues[key.lowercase()]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return findLoose(text, *keys)
        }

        val accessToken = findValue("accessToken", "access_token", "apiKey", "api_key", "token")
        val sessionToken = findValue("sessionToken", "session_token")
        val cookie = findValue("cookie", "cookies")
        val endpoint = findValue("endpoint", "baseUrl", "base_url", "url")
        val model = findValue("model").ifBlank { "auto" }
        val explicitType = findValue("provider", "type").uppercase()

        val type = when {
            explicitType.contains("WEB") || explicitType.contains("CHATGPT") -> "CHATGPT_WEB"
            explicitType.contains("CHAT_COMPLETIONS") || explicitType.contains("CHAT-COMPLETIONS") -> "CHAT_COMPLETIONS"
            explicitType.contains("LOCAL") -> "LOCAL"
            sessionToken.isNotBlank() -> "CHATGPT_WEB"
            accessToken.count { it == '.' } == 2 && endpoint.isBlank() -> "CHATGPT_WEB"
            else -> "RESPONSES"
        }

        val resolvedBaseUrl = when {
            endpoint.isNotBlank() -> endpoint
            type == "CHATGPT_WEB" -> "https://chatgpt.com/"
            else -> "https://api.openai.com/v1"
        }

        require(accessToken.isNotBlank() || sessionToken.isNotBlank() || type == "LOCAL") {
            "没有识别到 accessToken / API Key / sessionToken"
        }

        return AiProviderConfig(
            type = type,
            name = "imported",
            baseUrl = resolvedBaseUrl,
            apiKey = accessToken,
            sessionToken = sessionToken,
            cookie = cookie,
            model = model,
            extraHeadersJson = "{}"
        )
    }

    private fun stripCodeFence(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', text)
            if (text.endsWith("```")) text = text.dropLast(3)
        }
        return text.trim()
    }

    private fun parseJsonValues(text: String): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        runCatching {
            val root = JSONObject(text)
            collectJson(root, out)
        }
        return out
    }

    private fun collectJson(value: Any?, out: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    when (child) {
                        is JSONObject, is JSONArray -> collectJson(child, out)
                        null, JSONObject.NULL -> Unit
                        else -> if (!out.containsKey(key.lowercase())) out[key.lowercase()] = child.toString()
                    }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectJson(value.opt(i), out)
        }
    }

    private fun findLoose(text: String, vararg keys: String): String {
        for (key in keys) {
            val escaped = Regex.escape(key)
            val quoted = Regex("""(?is)[\"']?$escaped[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']""")
                .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (quoted.isNotEmpty()) return quoted

            val bare = Regex("""(?im)^\s*$escaped\s*[:=]\s*([^\s,}]+)""")
                .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (bare.isNotEmpty()) return bare
        }
        return ""
    }
}
