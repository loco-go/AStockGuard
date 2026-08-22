package com.locogo.astockguard.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locogo.astockguard.ui.chart.EquityCurve

@Composable
fun PaperReplayCard(
    state: MainUiState,
    onPaperBuy: () -> Unit,
    onPaperSell: () -> Unit,
    onResetPaper: () -> Unit,
    onReplayReset: () -> Unit,
    onReplayStep: () -> Unit,
    onReplayPlay: () -> Unit,
    onReplayPause: () -> Unit,
    onRunBacktest: () -> Unit
) {
    val paper = state.paperSummary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模拟盘 / 策略回放", fontWeight = FontWeight.Bold)
            Text("仅研究模拟，不会向券商发送委托。", style = MaterialTheme.typography.labelSmall)
            if (state.paperLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("净值 %.2f".format(paper.equity))
                Text("现金 %.2f".format(paper.cash))
                Text("收益 %+.2f%%".format(paper.returnPct))
            }
            if (paper.positions.isEmpty()) {
                Text("模拟持仓为空", style = MaterialTheme.typography.bodySmall)
            } else {
                paper.positions.take(6).forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.code, style = MaterialTheme.typography.bodySmall)
                        Text("${p.quantity}股 @ %.2f".format(p.avgCost), style = MaterialTheme.typography.bodySmall)
                        Text("浮盈 %+.0f".format(p.unrealizedPnl), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPaperBuy, enabled = !state.paperLoading && state.selectedCode != null, modifier = Modifier.weight(1f)) { Text("模拟买100") }
                OutlinedButton(onClick = onPaperSell, enabled = !state.paperLoading && state.selectedCode != null, modifier = Modifier.weight(1f)) { Text("模拟卖100") }
                TextButton(onClick = onResetPaper, enabled = !state.paperLoading) { Text("重置10万") }
            }

            HorizontalDivider()
            Text("1分钟盘中 Replay · ${state.selectedCode ?: "未选股"}", style = MaterialTheme.typography.titleSmall)
            val bars = state.minuteBars
            val idx = state.replayIndex
            val frame = bars.getOrNull(idx)
            Text(
                if (frame == null) "点击重置/播放开始逐帧查看" else "${idx + 1}/${bars.size} ${frame.time} 价=%.2f 均价=%.2f 量=%.0f".format(frame.price, frame.avgPrice, frame.volume),
                style = MaterialTheme.typography.bodySmall
            )
            if (bars.isNotEmpty()) LinearProgressIndicator(progress = { ((idx + 1).coerceAtLeast(0).toFloat() / bars.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onReplayReset, modifier = Modifier.weight(1f)) { Text("重置") }
                OutlinedButton(onClick = onReplayStep, modifier = Modifier.weight(1f)) { Text("下一帧") }
                if (state.replayRunning) Button(onClick = onReplayPause, modifier = Modifier.weight(1f)) { Text("暂停") }
                else Button(onClick = onReplayPlay, modifier = Modifier.weight(1f)) { Text("5x播放") }
            }
            Button(onClick = onRunBacktest, enabled = bars.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("运行 VWAP 策略回放") }

            state.replayReport?.let { r ->
                Text("VWAP_RECLAIM 结果", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("收益 %+.2f%%".format(r.returnPct), style = MaterialTheme.typography.bodySmall)
                    Text("最大回撤 %.2f%%".format(r.maxDrawdownPct), style = MaterialTheme.typography.bodySmall)
                    Text("胜率 %.1f%%".format(r.winRatePct), style = MaterialTheme.typography.bodySmall)
                }
                Text("平仓 ${r.closedTrades} 次 · Profit Factor ${if (r.profitFactor.isFinite()) "%.2f".format(r.profitFactor) else "∞"}", style = MaterialTheme.typography.bodySmall)
                EquityCurve(r.equityCurve)
                r.trades.takeLast(8).forEach { t ->
                    Text("${t.time} ${t.side} ${t.quantity}@%.2f  ${t.reason}".format(t.price), style = MaterialTheme.typography.labelSmall)
                }
                Text("回放只说明这组历史分钟数据在该固定规则下的表现，不代表未来收益。", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
