package com.locogo.astockguard.integration.ths

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.data.local.TradeRecordEntity
import com.locogo.astockguard.designsystem.AStockGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ThsTradeSyncActivity : AppCompatActivity() {
    private val statusText = mutableStateOf("检查同步状态…")
    private val tradeCount = mutableIntStateOf(0)
    private val accessibilityEnabled = mutableStateOf(false)

    private val openTradeFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importTradeFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AStockGuardTheme {
                Surface(Modifier.fillMaxSize()) {
                    TradeSyncScreen(
                        enabled = accessibilityEnabled.value,
                        count = tradeCount.intValue,
                        status = statusText.value,
                        onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onImport = { openTradeFile.launch(arrayOf("text/csv", "text/tab-separated-values", "text/plain", "application/csv", "*/*")) },
                        onRefresh = ::refreshStatus
                    )
                }
            }
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        accessibilityEnabled.value = isTradeAccessibilityEnabled()
        lifecycleScope.launch {
            tradeCount.intValue = withContext(Dispatchers.IO) { appContainer.database.cacheDao().getTradeRecords().count { it.source.startsWith("THS_") } }
            statusText.value = if (accessibilityEnabled.value) {
                "只读同步已启用。请手动打开同花顺的当日成交/历史成交/交割单页面，AStockGuard 会解析可访问的成交字段。"
            } else {
                "只读同步未启用。点击下方按钮后，在系统辅助功能中开启“AStockGuard / 同花顺成交只读同步”。"
            }
        }
    }

    private fun importTradeFile(uri: Uri) = lifecycleScope.launch {
        statusText.value = "正在读取交割单…"
        val text = runCatching {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val sb = StringBuilder()
                    val buffer = CharArray(16 * 1024)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n <= 0) break
                        sb.append(buffer, 0, n)
                        require(sb.length <= 10_000_000) { "交割单文件过大" }
                    }
                    sb.toString()
                } ?: error("无法读取文件")
            }
        }.getOrElse {
            statusText.value = "读取失败：${it.message}"
            return@launch
        }

        val parsed = ThsTradeFileParser.parse(text)
        if (parsed.isEmpty()) {
            statusText.value = "没有识别到成交记录。目前文件导入支持 CSV/TSV/TXT，表头需包含代码、买卖方向、成交价、成交数量；若你的券商只能导出 XLS/XLSX，后续再针对该格式适配。"
            return@launch
        }

        val inserted = withContext(Dispatchers.IO) {
            val dao = appContainer.database.cacheDao()
            val existing = dao.getTradeRecords()
            var count = 0
            parsed.forEach { trade ->
                val duplicate = existing.any { row ->
                    row.code == trade.code && row.side.equals(trade.side, true) && row.quantity == trade.quantity &&
                        abs(row.price - trade.price) < 0.0001 && abs(row.tradeAt - trade.tradeAt) <= 90_000L
                }
                if (!duplicate) {
                    dao.insertTradeRecord(
                        TradeRecordEntity(
                            tradeAt = trade.tradeAt,
                            code = trade.code,
                            side = trade.side,
                            quantity = trade.quantity,
                            price = trade.price,
                            source = "THS_FILE",
                            note = "同花顺/券商交割单文件导入"
                        )
                    )
                    count++
                }
            }
            count
        }
        statusText.value = "交割单解析 ${parsed.size} 条，本次新增 $inserted 条。成交点会在对应股票的 ECharts 图表中显示。"
        refreshStatus()
    }

    private fun isTradeAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ThsTradeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}

@Composable
private fun TradeSyncScreen(
    enabled: Boolean,
    count: Int,
    status: String,
    onOpenAccessibility: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("同花顺交易同步", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("把真实成交同步为图表买卖点和交易复盘数据。当前实现只读，不会调用辅助功能自动点击或提交委托。", color = MaterialTheme.colorScheme.onSurfaceVariant)

        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (enabled) "● 辅助功能已启用" else "○ 辅助功能未启用", fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("已同步成交：$count 条")
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) { Text("打开系统辅助功能设置") }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新同步状态") }
            }
        }

        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("交割单文件导入", fontWeight = FontWeight.Bold)
                Text("推荐作为准确账本：优先读取同花顺/券商导出的 CSV、TSV 或 TXT。辅助功能用于盘中快速补充，两条路径最终都写入同一个 TradeRecord。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("选择交割单文件") }
            }
        }

        Text("隐私：不会保存辅助功能原始页面文本；只保存股票代码、买卖方向、成交价、数量和时间。账户密码、验证码、资金账号不会进入同步记录。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
