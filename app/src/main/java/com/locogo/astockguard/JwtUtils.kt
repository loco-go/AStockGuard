package com.locogo.astockguard

/*
 * 文件职责：仅解析 JWT 的公开负载以辅助显示有效期；不验证签名，也不能把解析成功当成认证成功。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
