package com.locogo.astockguard

object PromptBuilder {
    fun build(snapshot: MonitorSnapshot, positions: List<Position>): String {
        val posByCode = positions.associateBy { it.code }
        val sb = StringBuilder()
        sb.appendLine("当前总仓位：${"%.1f".format(snapshot.positionRatio)}%")
        sb.appendLine("本地风险模型：${snapshot.assessment.eventRisk}/${snapshot.assessment.marketPhase}")
        sb.appendLine("本地建议上限：${"%.0f".format(snapshot.assessment.maxPositionRatio * 100)}%")
        sb.appendLine("观察池平均涨跌：${"%+.2f".format(snapshot.assessment.avgChange)}%")
        sb.appendLine("行情：")
        snapshot.quotes.forEach { q ->
            val pos = posByCode[q.code]
            sb.append("- ${q.code}")
            if (pos != null) sb.append(" ${pos.name} shares=${pos.shares} cost=${pos.cost} role=${pos.role}")
            sb.append(" latest=${q.latest ?: "?"} open=${q.open ?: "?"} low=${q.low ?: "?"} high=${q.high ?: "?"}")
            sb.appendLine(" change=${q.changeRatio?.let { "%+.2f%%".format(it) } ?: "?"}")
        }
        sb.appendLine("本地信号：")
        snapshot.assessment.signals.forEach { sb.appendLine("- ${it.code} ${it.action}: ${it.reason}") }
        sb.appendLine("请优先回答：1) 当前应该升仓/维持/降仓；2) 哪些是核心、哪些应减；3) 哪个条件出现后允许提高仓位。若数据不足必须明确说不足。")
        return sb.toString()
    }
}
