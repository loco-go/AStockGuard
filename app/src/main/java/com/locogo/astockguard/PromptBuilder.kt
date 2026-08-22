package com.locogo.astockguard

object PromptBuilder {
    fun build(snapshot: MonitorSnapshot, positions: List<Position>, question: String = ""): String {
        val pos = positions.associateBy { it.code }
        return buildString {
            appendLine("用户问题：${question.ifBlank { "根据当前快照给出风险与仓位建议" }}")
            appendLine("当前总仓位：${"%.1f".format(snapshot.positionRatio)}%")
            appendLine("本地风险模型：${snapshot.assessment.eventRisk}/${snapshot.assessment.marketPhase}")
            appendLine("建议仓位上限：${"%.0f".format(snapshot.assessment.maxPositionRatio * 100)}%")
            appendLine("观察池平均涨跌：${"%+.2f".format(snapshot.assessment.avgChange)}%")
            appendLine("行情：")
            snapshot.quotes.forEach { q ->
                val p = pos[q.code]
                append("- ${q.code} ${q.name}")
                if (p != null) append(" shares=${p.shares} cost=${p.cost} role=${p.role}")
                append(" latest=${q.latest ?: "?"} change=${q.changeRatio ?: "?"}% vwap=${q.vwap ?: "?"}")
                append(" ma5=${q.ma5 ?: "?"} ma10=${q.ma10 ?: "?"} R2=${q.r2Grade}/${q.r2Score}")
                appendLine()
            }
            appendLine("本地信号：")
            snapshot.assessment.signals.forEach { appendLine("- ${it.code} ${it.action}: ${it.reason}") }
            appendLine("数据状态：${if (snapshot.dataHealth.isStale) "缓存/非实时" else "实时"} ${snapshot.dataHealth.source} ${snapshot.dataHealth.message}")
            appendLine("请优先回答：1) 升仓/维持/降仓；2) 哪些持仓最弱；3) 允许升仓的确认条件；4) 降仓触发条件。数据不足必须明确说明。")
            appendLine("最后必须输出一段机器可解析 JSON，并严格包在 <ASTOCK_STRATEGY> 与 </ASTOCK_STRATEGY> 标签中。")
            appendLine("JSON格式：{\"market_action\":\"RAISE|HOLD|REDUCE\",\"confidence\":0-100,\"target_position_pct\":0-100,\"risk_level\":\"LOW|MEDIUM|HIGH\",\"stocks\":[{\"symbol\":\"股票代码\",\"action\":\"BUY|HOLD|WATCH|SELL\",\"confidence\":0-100,\"target_position_pct\":0-100,\"quantity\":整数,\"trigger\":\"触发条件\",\"invalid_if\":\"失效条件\",\"reason\":\"一句话理由\"}]}。")
        }
    }
}
