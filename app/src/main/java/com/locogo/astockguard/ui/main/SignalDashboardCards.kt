package com.locogo.astockguard.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun R2ScannerCard(state: MainUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("R2 扫描器", fontWeight = FontWeight.Bold)
            if (state.r2ScanRows.isEmpty()) Text("当前没有 R2 50分以上候选", style = MaterialTheme.typography.bodySmall)
            else state.r2ScanRows.take(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.code)
                    Text("${row.grade}/${row.score} · ${row.stageHint.name}", fontWeight = FontWeight.Medium)
                }
                Text(row.reason, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SignalLifecycleCard(state: MainUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("信号生命周期", fontWeight = FontWeight.Bold)
            if (state.signalStates.isEmpty()) Text("启动实时监控后会建立信号状态。", style = MaterialTheme.typography.bodySmall)
            else state.signalStates.take(12).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.code)
                    Text("${row.stage} · ${row.lastAction}", fontWeight = FontWeight.Medium)
                }
            }
            Text("通知仅在 TRIGGERED / CONFIRMED / INVALIDATED 等重要状态变化时触发，并有冷却去重。", style = MaterialTheme.typography.labelSmall)
        }
    }
}
