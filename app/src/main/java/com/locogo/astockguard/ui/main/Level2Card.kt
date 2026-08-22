package com.locogo.astockguard.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locogo.astockguard.data.level2.Level2Level
import com.locogo.astockguard.data.level2.Level2Snapshot

@Composable
fun Level2Card(snapshot: Level2Snapshot?, loading: Boolean, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Level2 十档盘口", fontWeight = FontWeight.Bold)
                    val tag = when {
                        snapshot == null -> "未加载"
                        snapshot.simulated -> "MOCK · 模拟数据"
                        snapshot.stale -> "${snapshot.source} · STALE 缓存"
                        else -> "${snapshot.source} · 授权真实源"
                    }
                    Text(tag, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "刷新中" else "刷新盘口") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (snapshot == null) {
                Text("暂无盘口。默认 MOCK 只用于 UI/回放；真实 Level2 需在设置中配置已授权的 HTTP JSON Provider。", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            if (snapshot.simulated) {
                Text("⚠ 当前是模拟盘口，不会作为 AI 实盘证据。", style = MaterialTheme.typography.bodySmall)
            } else if (snapshot.stale) {
                Text("⚠ 实时源不可用，当前为缓存盘口，不得用于即时交易判断。", style = MaterialTheme.typography.bodySmall)
            }
            Text(snapshot.message, style = MaterialTheme.typography.labelSmall)

            HorizontalDivider()
            Text("卖盘", style = MaterialTheme.typography.labelLarge)
            snapshot.asks.take(10).asReversed().forEachIndexed { index, level ->
                val n = snapshot.asks.take(10).size - index
                BookRow("卖$n", level)
            }
            HorizontalDivider()
            Text("买盘", style = MaterialTheme.typography.labelLarge)
            snapshot.bids.take(10).forEachIndexed { index, level -> BookRow("买${index + 1}", level) }

            if (snapshot.trades.isNotEmpty()) {
                HorizontalDivider()
                Text("最近成交", style = MaterialTheme.typography.labelLarge)
                snapshot.trades.take(12).forEach { trade ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(trade.time, style = MaterialTheme.typography.bodySmall)
                        Text(trade.side, style = MaterialTheme.typography.bodySmall)
                        Text("%.2f".format(trade.price), style = MaterialTheme.typography.bodySmall)
                        Text("${trade.volume}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(label: String, level: Level2Level) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.width(42.dp), style = MaterialTheme.typography.bodySmall)
        Text("%.2f".format(level.price), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(level.volume.toString(), style = MaterialTheme.typography.bodySmall)
    }
}
