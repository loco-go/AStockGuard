package com.locogo.astockguard.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locogo.astockguard.Position
import com.locogo.astockguard.data.fundflow.SectorFundFlowResult
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.domain.trading.TTradePlan
import com.locogo.astockguard.ui.chart.EChartsTradingChart
import com.locogo.astockguard.ui.chart.EquityCurve
import com.locogo.astockguard.ui.chart.TradingChartMode

private enum class HomeSection(val label: String, val short: String) {
    DECISION("决策", "决"), CHART("图表", "图"), FLOW("资金", "资"), REVIEW("复盘", "复")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: MainUiState,
    positions: List<Position>,
    onRefresh: () -> Unit,
    onAnalyze: (String) -> Unit,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
    onSettings: () -> Unit,
    onSelectStock: (String) -> Unit,
    onSectorType: (String) -> Unit,
    onRecordTrade: (String, Int, Double) -> Unit,
    onRefreshNews: () -> Unit,
    onRefreshLevel2: () -> Unit,
    onPaperBuy: () -> Unit,
    onPaperSell: () -> Unit,
    onResetPaper: () -> Unit,
    onReplayReset: () -> Unit,
    onReplayStep: () -> Unit,
    onReplayPlay: () -> Unit,
    onReplayPause: () -> Unit,
    onRunBacktest: () -> Unit,
    onSetBuyAnchor: (Double) -> Unit,
    onClearBuyAnchor: () -> Unit
) {
    var question: String by rememberSaveable { mutableStateOf("") }
    var sectionName by rememberSaveable { mutableStateOf(HomeSection.DECISION.name) }
    var chartModeName by rememberSaveable { mutableStateOf(TradingChartMode.MINUTE.name) }
    val section = runCatching { HomeSection.valueOf(sectionName) }.getOrDefault(HomeSection.DECISION)
    val chartMode = runCatching { TradingChartMode.valueOf(chartModeName) }.getOrDefault(TradingChartMode.MINUTE)
    val snapshot = state.snapshot
    val rows = remember(snapshot, positions, state.aiStrategy) { StrategyUiMapper.map(snapshot, positions, state.aiStrategy) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("AStockGuard", fontWeight = FontWeight.SemiBold)
                        Text(state.selectedCode ?: "交易驾驶舱", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { TextButton(onClick = onSettings) { Text("设置") } }
            )
        },
        bottomBar = {
            NavigationBar {
                HomeSection.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { sectionName = item.name },
                        icon = { Text(item.short, fontWeight = FontWeight.Bold) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { TradingHeroCard(state, positions) }
            item { QuickActions(state, onRefresh, onStartMonitor, onStopMonitor) }

            when (section) {
                HomeSection.DECISION -> {
                    item { TPlanCard(state.tTradePlan, onClearBuyAnchor) }
                    item { SectionTitle("持仓 / 观察池") }
                    if (rows.isEmpty()) item { EmptyCard("暂无行情，请先刷新。") }
                    else items(rows, key = { it.code }) { row -> StrategyRow(row) { onSelectStock(row.code) } }
                    item { R2ScannerCard(state) }
                    item { SignalLifecycleCard(state) }
                    item { NewsRiskCard(state, onRefreshNews) }
                    item { AiDecisionCard(state, question, { question = it }, { onAnalyze(question) }) }
                }

                HomeSection.CHART -> {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = chartMode == TradingChartMode.MINUTE,
                                onClick = { chartModeName = TradingChartMode.MINUTE.name },
                                label = { Text("分时T") }
                            )
                            FilterChip(
                                selected = chartMode == TradingChartMode.DAILY,
                                onClick = { chartModeName = TradingChartMode.DAILY.name },
                                label = { Text("日K") }
                            )
                            if (state.manualBuyAnchor != null) {
                                AssistChip(onClick = onClearBuyAnchor, label = { Text("清除手选买点") })
                            }
                        }
                    }
                    item {
                        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(state.selectedCode ?: "请选择股票", fontWeight = FontWeight.Bold)
                                        Text("点击图上价格可设为计划买点锚点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("ECharts 6.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                EChartsTradingChart(
                                    dailyBars = state.dailyBars,
                                    minuteBars = state.minuteBars,
                                    trades = state.tradeRecords,
                                    plan = state.tTradePlan,
                                    mode = chartMode,
                                    onBuyAnchor = onSetBuyAnchor
                                )
                            }
                        }
                    }
                    item { TPlanCard(state.tTradePlan, onClearBuyAnchor) }
                    item { TradeMarkerCard(state) }
                    item { SectionTitle("Level2 盘口") }
                    item { Level2Card(state.level2, state.level2Loading, onRefreshLevel2) }
                }

                HomeSection.FLOW -> {
                    item { SectionTitle("个股资金") }
                    item { StockFundFlowCard(state.stockFundFlow, state.fundFlowLoading) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = state.sectorFundFlow?.type == "INDUSTRY", onClick = { onSectorType("INDUSTRY") }, label = { Text("行业") })
                            FilterChip(selected = state.sectorFundFlow?.type == "CONCEPT", onClick = { onSectorType("CONCEPT") }, label = { Text("概念") })
                        }
                    }
                    item { SectorFundFlowCard(state.sectorFundFlow) }
                    item { SectionTitle("组合净值") }
                    item {
                        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("持仓组合净值", fontWeight = FontWeight.Bold)
                                Text("基准=100，按当前持股数量回放近30日", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                EquityCurve(state.equityCurve)
                            }
                        }
                    }
                }

                HomeSection.REVIEW -> {
                    item { ReviewDashboardCard(state, onRecordTrade) }
                    item { SectionTitle("模拟盘 / Replay") }
                    item {
                        PaperReplayCard(
                            state = state,
                            onPaperBuy = onPaperBuy,
                            onPaperSell = onPaperSell,
                            onResetPaper = onResetPaper,
                            onReplayReset = onReplayReset,
                            onReplayStep = onReplayStep,
                            onReplayPlay = onReplayPlay,
                            onReplayPause = onReplayPause,
                            onRunBacktest = onRunBacktest
                        )
                    }
                    item { AiDecisionCard(state, question, { question = it }, { onAnalyze(question) }) }
                }
            }
        }
    }
}

@Composable
private fun TradingHeroCard(state: MainUiState, positions: List<Position>) {
    val snapshot = state.snapshot
    val assessment = snapshot?.assessment
    val code = state.selectedCode
    val quote = snapshot?.quotes?.firstOrNull { it.code == code }
    val position = positions.firstOrNull { it.code == code }
    val pnlPct = if (quote?.latest != null && position != null && position.cost > 0) (quote.latest / position.cost - 1.0) * 100.0 else null
    val localAction = snapshot?.assessment?.signals?.firstOrNull { it.code == code }?.action ?: "HOLD"
    val aiAction = state.aiStrategy?.stocks?.firstOrNull { stock -> code?.substringBefore(".")?.let { stock.symbol.contains(it) } == true }?.action ?: "-"

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text(quote?.name ?: code ?: "市场概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(quote?.latest?.let { "%.2f".format(it) } ?: "--", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(quote?.changeRatio?.let { "%+.2f%%".format(it) } ?: "", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("${assessment?.eventRisk ?: "E?"} / ${assessment?.marketPhase ?: "M?"}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.Bold)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroMetric("仓位", snapshot?.positionRatio?.let { "%.1f%%".format(it) } ?: "-")
                HeroMetric("持仓盈亏", pnlPct?.let { "%+.2f%%".format(it) } ?: "-")
                HeroMetric("R2", quote?.let { "${it.r2Grade}/${it.r2Score}" } ?: "-")
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("本地 $localAction", fontWeight = FontWeight.SemiBold)
                Text("AI $aiAction", fontWeight = FontWeight.SemiBold)
                Text(if (snapshot?.dataHealth?.isStale == true) "STALE" else "LIVE", color = if (snapshot?.dataHealth?.isStale == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RowScope.HeroMetric(label: String, value: String) {
    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickActions(state: MainUiState, onRefresh: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.weight(1.2f)) { Text(if (state.loading) "刷新中" else "刷新行情") }
        OutlinedButton(onClick = onStart, modifier = Modifier.weight(1f)) { Text("盯盘") }
        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
    }
}

@Composable
private fun TPlanCard(plan: TTradePlan?, onClearAnchor: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("T交易计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(plan?.status?.let(::tStatusLabel) ?: "等待分钟数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (plan?.manualAnchorPrice != null) TextButton(onClick = onClearAnchor) { Text("清除锚点") }
            }
            if (plan == null || plan.buyZoneLow <= 0.0) {
                Text(plan?.reason ?: "选择持仓股票后计算T区间。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanMetric("买入区", "%.2f~%.2f".format(plan.buyZoneLow, plan.buyZoneHigh), Modifier.weight(1f))
                PlanMetric("卖出区", "%.2f~%.2f".format(plan.sellZoneLow, plan.sellZoneHigh), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanMetric("失效位", "%.2f".format(plan.invalidPrice), Modifier.weight(1f))
                PlanMetric("建议T仓", "${plan.suggestedQuantity}股", Modifier.weight(1f))
                PlanMetric("计划空间", "%.2f%%".format(plan.expectedEdgePct), Modifier.weight(1f))
            }
            Text(plan.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("仅用于执行辅助：A股T+1下建议使用已有底仓做T；实际成交与计划点分开记录。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun tStatusLabel(status: String): String = when (status) {
    "BUY_ZONE" -> "进入计划买入区，等待承接/资金确认"
    "SELL_ZONE" -> "进入计划卖出区，可评估卖T仓"
    "WAIT_RECLAIM" -> "价格偏弱，等待重新站回计划区"
    "WAIT" -> "区间中部，等待更好的盈亏比"
    "INVALIDATED" -> "计划失效，禁止机械补仓"
    "BLOCKED_STALE" -> "行情过期，禁止生成实时动作"
    "NO_T" -> "当前振幅/空间不足，不建议强行做T"
    "NO_BASE_POSITION" -> "缺少可用于T交易的底仓"
    else -> status
}

@Composable
private fun TradeMarkerCard(state: MainUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("成交标记", fontWeight = FontWeight.Bold)
            if (state.tradeRecords.isEmpty()) {
                Text("暂无同步/手工成交记录。后续同花顺成交同步后会直接显示到图上。", style = MaterialTheme.typography.bodySmall)
            } else {
                state.tradeRecords.takeLast(8).reversed().forEach { trade ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${if (trade.side.uppercase() == "BUY") "买" else "卖"} ${trade.quantity}股 · ${trade.source}")
                        Text("%.2f".format(trade.price), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiDecisionCard(state: MainUiState, question: String, onQuestion: (String) -> Unit, onAnalyze: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI 综合判断", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = question,
                onValueChange = onQuestion,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("补充问题，可留空") },
                minLines = 2,
                maxLines = 4
            )
            Button(onClick = onAnalyze, enabled = !state.aiLoading && state.snapshot != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.aiLoading) "AI 分析中…" else "后台 AI 分析")
            }
            Text(state.aiText, style = MaterialTheme.typography.bodySmall, maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewsRiskCard(state: MainUiState, onRefreshNews: () -> Unit) {
    val news = state.newsRisk
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("新闻 / 黑天鹅", fontWeight = FontWeight.Bold)
                    Text("${news.level} · score ${news.score}${if (news.stale) " · 缓存" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefreshNews, enabled = !state.newsLoading) { Text(if (state.newsLoading) "刷新中" else "刷新") }
            }
            if (state.newsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (news.evidence.isEmpty()) Text("24小时内没有命中高风险证据。", style = MaterialTheme.typography.bodySmall)
            else news.evidence.take(4).forEach { item -> Text("• [${item.source}] ${item.title}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun StrategyRow(row: StockStrategyUiModel, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("${row.name}  ${row.code}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${row.role} · R2 ${row.r2Grade}/${row.r2Score}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.price?.let { "%.2f".format(it) } ?: "-", fontWeight = FontWeight.SemiBold)
                    Text(row.changeRatio?.let { "%+.2f%%".format(it) } ?: "-")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("本地 ${row.localAction}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(if (row.aiAction == "-") "AI 未分析" else "AI ${row.aiAction} ${row.aiConfidence}%", style = MaterialTheme.typography.labelMedium)
                if (row.conflict) Text("⚠ 冲突", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                else if (row.stale) Text("STALE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            val reason = if (row.aiReason.isNotBlank()) row.aiReason else row.localReason
            if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockFundFlowCard(flow: StockFundFlow?, loading: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("个股资金", fontWeight = FontWeight.Bold)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (flow == null) Text("暂无资金流数据", style = MaterialTheme.typography.bodySmall)
            else {
                Text("${flow.source}${if (flow.stale) " · 缓存" else " · 最新"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                flow.periods.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${p.days}日")
                        Text("主力 ${formatMoney(p.mainNet)}")
                        Text("超大 ${formatMoney(p.superLargeNet)}")
                    }
                }
                flow.minute.lastOrNull()?.let { last ->
                    Text("${last.time} 主力 ${formatMoney(last.mainNet)} · 大单 ${formatMoney(last.largeNet)} · 中单 ${formatMoney(last.mediumNet)} · 小单 ${formatMoney(last.smallNet)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("资金分类为数据商口径，不等同于真实机构账户身份。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectorFundFlowCard(result: SectorFundFlowResult?) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("板块资金排行", fontWeight = FontWeight.Bold)
            if (result == null || result.rows.isEmpty()) Text("暂无板块资金数据", style = MaterialTheme.typography.bodySmall)
            else {
                Text("${result.type} · ${result.source}${if (result.stale) " · 缓存" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                result.rows.take(10).forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}. ${row.name}", modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${"%+.2f%%".format(row.changePct)}  ${formatMoney(row.mainNet)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(text, Modifier.padding(16.dp)) }
}

private fun formatMoney(value: Double): String = when {
    kotlin.math.abs(value) >= 100_000_000 -> "%+.2f亿".format(value / 100_000_000.0)
    kotlin.math.abs(value) >= 10_000 -> "%+.1f万".format(value / 10_000.0)
    else -> "%+.0f".format(value)
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
