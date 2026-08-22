package com.locogo.astockguard

import android.util.Base64
import org.json.JSONObject

object JwtUtils {
    fun accountId(jwt: String): String {
        return runCatching {
            val p = jwt.split('.')
            if (p.size < 2) return ""
            val bytes = Base64.decode(p[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            root.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id").orEmpty()
        }.getOrDefault("")
    }
}
