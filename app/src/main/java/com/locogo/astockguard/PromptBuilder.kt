package com.locogo.astockguard

object PromptBuilder {
    fun build(
        snapshot: MonitorSnapshot,
        positions: List<Position>,
        question: String = "",
        stockFundFlow: com.locogo.astockguard.data.fundflow.StockFundFlow? = null,
        sectorFundFlow: com.locogo.astockguard.data.fundflow.SectorFundFlowResult? = null,
        newsRisk: com.locogo.astockguard.data.news.NewsRiskAssessment? = null,
        level2: com.locogo.astockguard.data.level2.Level2Snapshot? = null
    ): String {
        val pos = positions.associateBy { it.code }
        return buildString {
            appendLine("用户问题：${question.ifBlank { "根据当前快照给出风险与仓位建议" }}")
            appendLine("当前总仓位：${"%.1f".format(snapshot.positionRatio)}%")
            appendLine("本地风险模型：${snapshot.assessment.eventRisk}/${snapshot.assessment.marketPhase}")
            appendLine("建议仓位上限：${"%.0f".format(snapshot.assessment.maxPositionRatio * 100)}%")
            appendLine("观察池平均涨跌：${"%+.2f".format(snapshot.assessment.avgChange)}%")
            newsRisk?.let { n ->
                appendLine("新闻风险覆盖层：${n.level} score=${n.score} ${if (n.stale) "(缓存/可能过期)" else "(最新抓取)"}")
                n.evidence.take(5).forEach { appendLine("- [${it.source}] ${it.title}") }
                appendLine("新闻风险只作为风险覆盖层，不得单独覆盖本地 R2/VWAP/资金流规则；若证据不足必须明确说明。")
            }
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
            stockFundFlow?.let { flow ->
                appendLine("选中个股资金流（${flow.source}${if (flow.stale) "/缓存" else ""}）：")
                flow.periods.forEach { p ->
                    appendLine("- ${p.days}日 主力=${p.mainNet} 超大=${p.superLargeNet} 大单=${p.largeNet} 中单=${p.mediumNet} 小单=${p.smallNet}")
                }
            }
            sectorFundFlow?.let { sectors ->
                appendLine("${sectors.type}板块资金Top5（${sectors.source}）：")
                sectors.rows.take(5).forEach { appendLine("- ${it.name} 涨跌=${it.changePct}% 主力净流入=${it.mainNet} 主力占比=${it.mainPct}%") }
            }
            if (level2 != null) {
                if (level2.simulated) {
                    appendLine("Level2：当前仅有 MOCK 模拟盘口，因此不得作为 AI 实盘判断证据。")
                } else {
                    appendLine("Level2（${level2.source}${if (level2.stale) "/缓存STALE" else "/实时"}）：")
                    appendLine("- 买盘：${level2.bids.take(5).joinToString { "${it.price}@${it.volume}" }}")
                    appendLine("- 卖盘：${level2.asks.take(5).joinToString { "${it.price}@${it.volume}" }}")
                    appendLine("- 最近成交：${level2.trades.take(8).joinToString { "${it.time}:${it.side}:${it.price}@${it.volume}" }}")
                    if (level2.stale) appendLine("Level2 已过期，只能作为历史参考，不得据此给出即时买卖动作。")
                }
            }
            appendLine("资金流是数据商订单规模分类口径，只能作为一个因子，不得等价为机构真实买卖。")
            appendLine("数据状态：${if (snapshot.dataHealth.isStale) "缓存/非实时" else "实时"} ${snapshot.dataHealth.source} ${snapshot.dataHealth.message}")
            appendLine("请优先回答：1) 升仓/维持/降仓；2) 哪些持仓最弱；3) 允许升仓的确认条件；4) 降仓触发条件。数据不足必须明确说明。")
            appendLine("最后必须输出一段机器可解析 JSON，并严格包在 <ASTOCK_STRATEGY> 与 </ASTOCK_STRATEGY> 标签中。")
            appendLine("JSON格式：{\"market_action\":\"RAISE|HOLD|REDUCE\",\"confidence\":0-100,\"target_position_pct\":0-100,\"risk_level\":\"LOW|MEDIUM|HIGH\",\"stocks\":[{\"symbol\":\"股票代码\",\"action\":\"BUY|HOLD|WATCH|SELL\",\"confidence\":0-100,\"target_position_pct\":0-100,\"quantity\":整数,\"trigger\":\"触发条件\",\"invalid_if\":\"失效条件\",\"reason\":\"一句话理由\"}]}。")
        }
    }
}
