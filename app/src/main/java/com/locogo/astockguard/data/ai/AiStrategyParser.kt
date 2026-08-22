package com.locogo.astockguard.data.ai

import org.json.JSONObject

data class AiStockStrategy(
    val symbol: String,
    val action: String,
    val confidence: Int,
    val targetPositionPct: Int,
    val quantity: Int,
    val trigger: String,
    val invalidIf: String,
    val reason: String
)

data class AiStrategy(
    val marketAction: String,
    val confidence: Int,
    val targetPositionPct: Int,
    val riskLevel: String,
    val stocks: List<AiStockStrategy>,
    val json: String
)

object AiStrategyParser {
    private const val START = "<ASTOCK_STRATEGY>"
    private const val END = "</ASTOCK_STRATEGY>"

    fun parse(answer: String): AiStrategy? {
        val jsonText = extractJson(answer) ?: return null
        return runCatching {
            val obj = JSONObject(jsonText)
            val stocksJson = obj.optJSONArray("stocks")
            val stocks = buildList {
                if (stocksJson != null) {
                    for (i in 0 until stocksJson.length()) {
                        val s = stocksJson.optJSONObject(i) ?: continue
                        add(
                            AiStockStrategy(
                                symbol = s.optString("symbol"),
                                action = s.optString("action", "HOLD"),
                                confidence = s.optInt("confidence", 0).coerceIn(0, 100),
                                targetPositionPct = s.optInt("target_position_pct", 0).coerceIn(0, 100),
                                quantity = s.optInt("quantity", 0),
                                trigger = s.optString("trigger"),
                                invalidIf = s.optString("invalid_if"),
                                reason = s.optString("reason")
                            )
                        )
                    }
                }
            }
            AiStrategy(
                marketAction = obj.optString("market_action", "HOLD"),
                confidence = obj.optInt("confidence", 0).coerceIn(0, 100),
                targetPositionPct = obj.optInt("target_position_pct", 0).coerceIn(0, 100),
                riskLevel = obj.optString("risk_level", "UNKNOWN"),
                stocks = stocks,
                json = obj.toString()
            )
        }.getOrNull()
    }

    fun displayText(answer: String): String {
        val s = parse(answer) ?: return answer
        return buildString {
            appendLine("AI策略：${s.marketAction}  置信度 ${s.confidence}%  目标仓位 ${s.targetPositionPct}%")
            appendLine("风险：${s.riskLevel}")
            if (s.stocks.isNotEmpty()) {
                appendLine()
                s.stocks.forEach {
                    append("${it.symbol}  ${it.action} ${it.confidence}%")
                    if (it.quantity != 0) append("  数量 ${it.quantity}")
                    if (it.targetPositionPct > 0) append("  目标仓位 ${it.targetPositionPct}%")
                    appendLine()
                    if (it.trigger.isNotBlank()) appendLine("触发：${it.trigger}")
                    if (it.invalidIf.isNotBlank()) appendLine("失效：${it.invalidIf}")
                    if (it.reason.isNotBlank()) appendLine("原因：${it.reason}")
                    appendLine()
                }
            }
            appendLine("—— AI原始回复 ——")
            append(answer)
        }
    }

    private fun extractJson(answer: String): String? {
        val start = answer.indexOf(START)
        val end = answer.indexOf(END)
        if (start >= 0 && end > start) {
            return answer.substring(start + START.length, end).trim()
        }
        val first = answer.indexOf('{')
        val last = answer.lastIndexOf('}')
        return if (first >= 0 && last > first) answer.substring(first, last + 1) else null
    }
}
