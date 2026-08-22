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
import com.locogo.astockguard.ui.chart.CandlestickChart
import com.locogo.astockguard.ui.chart.ChartSignal
import com.locogo.astockguard.ui.chart.EquityCurve
import com.locogo.astockguard.ui.chart.MinuteChart

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
    onRecordTrade: (String, Int, Double) -> Unit
) {
    var question: String by rememberSaveable { mutableStateOf("") }
    val snapshot = state.snapshot
    val rows = remember(snapshot, positions, state.aiStrategy) { StrategyUiMapper.map(snapshot, positions, state.aiStrategy) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("A股实时交易驾驶舱", fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = onSettings) { Text("设置") } }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = {}, label = { Text("首页") })
                NavigationBarItem(selected = false, onClick = {}, icon = {}, label = { Text("行情") })
                NavigationBarItem(selected = false, onClick = {}, icon = {}, label = { Text("持仓") })
                NavigationBarItem(selected = false, onClick = {}, icon = {}, label = { Text("信号") })
                NavigationBarItem(selected = false, onClick = onSettings, icon = {}, label = { Text("设置") })
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { MarketStatusCard(state) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.weight(1f)) { Text(if (state.loading) "刷新中" else "刷新") }
                    OutlinedButton(onClick = onStartMonitor, modifier = Modifier.weight(1f)) { Text("启动监控") }
                    OutlinedButton(onClick = onStopMonitor, modifier = Modifier.weight(1f)) { Text("停止") }
                }
            }

            item { SectionTitle("持仓 / 观察池策略") }
            if (rows.isEmpty()) item { Text("暂无行情，请先刷新。") }
            else items(rows, key = { it.code }) { row -> StrategyRow(row) { onSelectStock(row.code) } }

            item { R2ScannerCard(state) }
            item { SignalLifecycleCard(state) }
            item { ReviewDashboardCard(state, onRecordTrade) }

            item { SectionTitle("资金流") }
            item { StockFundFlowCard(state.stockFundFlow, state.fundFlowLoading) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.sectorFundFlow?.type == "INDUSTRY", onClick = { onSectorType("INDUSTRY") }, label = { Text("行业") })
                    FilterChip(selected = state.sectorFundFlow?.type == "CONCEPT", onClick = { onSectorType("CONCEPT") }, label = { Text("概念") })
                }
            }
            item { SectorFundFlowCard(state.sectorFundFlow) }

            item { SectionTitle("分时 / K线") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.selectedCode ?: "请选择股票", fontWeight = FontWeight.Bold)
                        Text("分时：价格 + 均价", style = MaterialTheme.typography.labelMedium)
                        MinuteChart(state.minuteBars)
                        Text("日K：拖动查看 OHLC", style = MaterialTheme.typography.labelMedium)
                        val selectedDigits = state.selectedCode?.substringBefore(".").orEmpty()
                        val action = state.aiStrategy?.stocks?.firstOrNull { it.symbol.contains(selectedDigits) }?.action
                        val signals = if (action != null && state.dailyBars.isNotEmpty()) listOf(ChartSignal(state.dailyBars.lastIndex, action)) else emptyList()
                        CandlestickChart(state.dailyBars, signals)
                    }
                }
            }

            item { SectionTitle("持仓组合净值") }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("基准=100，按当前持股数量回放近30日", style = MaterialTheme.typography.bodySmall); EquityCurve(state.equityCurve) } } }

            item { SectionTitle("AI 综合判断") }
            item {
                OutlinedTextField(
                    value = question,
                    onValueChange = { value -> question = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("可选：补充你的问题") },
                    minLines = 2,
                    maxLines = 4
                )
            }
            item { Button(onClick = { onAnalyze(question) }, enabled = !state.aiLoading && snapshot != null, modifier = Modifier.fillMaxWidth()) { Text(if (state.aiLoading) "AI 分析中…" else "后台 AI 分析") } }
            item { Card(Modifier.fillMaxWidth()) { Text(state.aiText, Modifier.padding(14.dp)) } }
        }
    }
}

@Composable
private fun MarketStatusCard(state: MainUiState) {
    val s = state.snapshot; val a = s?.assessment
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("市场状态", style = MaterialTheme.typography.labelLarge); Text("${a?.eventRisk ?: "E?"}  ${a?.marketPhase ?: "M?"}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { Text("当前仓位"); Text(s?.positionRatio?.let { "%.1f%%".format(it) } ?: "-", fontWeight = FontWeight.Bold) }
            }
            Text("建议上限 ${a?.maxPositionRatio?.let { "%.0f%%".format(it * 100) } ?: "-"} · 观察池 ${a?.avgChange?.let { "%+.2f%%".format(it) } ?: "-"}")
            Text(s?.dataHealth?.let { "${it.source}${if (it.isStale) " · 缓存/禁止实时动作" else " · 实时"}" } ?: "尚未加载数据", style = MaterialTheme.typography.bodySmall)
            if (!a?.advice.isNullOrBlank()) Text(a!!.advice)
        }
    }
}

@Composable
private fun StrategyRow(row: StockStrategyUiModel, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("${row.name}  ${row.code}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${row.role} · R2 ${row.r2Grade}/${row.r2Score}", style = MaterialTheme.typography.bodySmall) }
                Column(horizontalAlignment = Alignment.End) { Text(row.price?.let { "%.2f".format(it) } ?: "-"); Text(row.changeRatio?.let { "%+.2f%%".format(it) } ?: "-") }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text("本地 ${row.localAction}") })
                AssistChip(onClick = {}, label = { Text(if (row.aiAction == "-") "AI 未分析" else "AI ${row.aiAction} ${row.aiConfidence}%") })
                if (row.conflict) SuggestionChip(onClick = {}, label = { Text("⚠ 冲突") })
                if (row.stale) SuggestionChip(onClick = {}, label = { Text("STALE") })
            }
            if (row.aiTargetPositionPct > 0) Text("AI目标仓位 ${row.aiTargetPositionPct}%", style = MaterialTheme.typography.bodySmall)
            val reason = if (row.aiReason.isNotBlank()) row.aiReason else row.localReason
            if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StockFundFlowCard(flow: StockFundFlow?, loading: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("个股资金", fontWeight = FontWeight.Bold)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (flow == null) Text("暂无资金流数据", style = MaterialTheme.typography.bodySmall)
            else {
                Text("数据源 ${flow.source}${if (flow.stale) " · 缓存" else " · 实时/最新"}", style = MaterialTheme.typography.bodySmall)
                flow.periods.forEach { p -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${p.days}日"); Text("主力 ${formatMoney(p.mainNet)}"); Text("超大 ${formatMoney(p.superLargeNet)}") } }
                flow.minute.lastOrNull()?.let { last -> Text("盘中 ${last.time}：主力 ${formatMoney(last.mainNet)} · 大单 ${formatMoney(last.largeNet)} · 中单 ${formatMoney(last.mediumNet)} · 小单 ${formatMoney(last.smallNet)}", style = MaterialTheme.typography.bodySmall) }
                Text("注：主力/大单属于东方财富数据商分类口径，不等同于交易所识别的真实机构账户。", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SectorFundFlowCard(result: SectorFundFlowResult?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("板块资金排行", fontWeight = FontWeight.Bold)
            if (result == null || result.rows.isEmpty()) Text("暂无板块资金数据", style = MaterialTheme.typography.bodySmall)
            else {
                Text("${result.type} · ${result.source}${if (result.stale) " · 缓存" else ""}", style = MaterialTheme.typography.bodySmall)
                result.rows.take(10).forEachIndexed { index, row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${index + 1}. ${row.name}", modifier = Modifier.weight(1f), maxLines = 1); Text("${"%+.2f%%".format(row.changePct)}  ${formatMoney(row.mainNet)}") } }
            }
        }
    }
}

private fun formatMoney(value: Double): String = when {
    kotlin.math.abs(value) >= 100_000_000 -> "%+.2f亿".format(value / 100_000_000.0)
    kotlin.math.abs(value) >= 10_000 -> "%+.1f万".format(value / 10_000.0)
    else -> "%+.0f".format(value)
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
