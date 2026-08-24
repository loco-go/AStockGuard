package com.locogo.astockguard.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ReviewDashboardCard(state: MainUiState, onRecordTrade: (String, Int, Double) -> Unit) {
    val selectedPrice = state.snapshot?.quotes?.firstOrNull { it.code == state.selectedCode }?.latest
    var quantityText by remember(state.selectedCode) { mutableStateOf("100") }
    var priceText by remember(state.selectedCode, selectedPrice) { mutableStateOf(selectedPrice?.let { "%.2f".format(it) }.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("信号 / 交易复盘", fontWeight = FontWeight.Bold)
            val s = state.signalReviewStats
            Text("信号：${s.evaluated}/${s.total} 已评估 · 胜率 ${"%.1f%%".format(s.winRate)} · 平均方向收益 ${"%+.2f%%".format(s.averageEdgePct)}")
            val t = state.tradeReviewStats
            Text("交易：${t.trades} 笔 · 已闭合 ${t.closedTrades} · 胜率 ${"%.1f%%".format(t.winRate)} · 已实现 ${"%+.2f".format(t.realizedPnl)}")
            HorizontalDivider()
            Text("记录当前选中股票的实际成交", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(quantityText, { quantityText = it.filter(Char::isDigit) }, label = { Text("数量") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(priceText, { priceText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("成交价") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRecordTrade("BUY", quantityText.toIntOrNull() ?: 0, priceText.toDoubleOrNull() ?: 0.0) }, modifier = Modifier.weight(1f)) { Text("记录买入") }
                OutlinedButton(onClick = { onRecordTrade("SELL", quantityText.toIntOrNull() ?: 0, priceText.toDoubleOrNull() ?: 0.0) }, modifier = Modifier.weight(1f)) { Text("记录卖出") }
            }
            s.recent.firstOrNull()?.let { r ->
                Text("最近信号 ${r.code} ${r.action} @ ${"%.2f".format(r.signalPrice)} · ${r.edgePct?.let { "%+.2f%%".format(it) } ?: "待评估"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
