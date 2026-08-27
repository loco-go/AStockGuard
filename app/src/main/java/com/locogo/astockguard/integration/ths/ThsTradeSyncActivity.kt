package com.locogo.astockguard.integration.ths

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.applySystemBarInsets
import com.locogo.astockguard.data.local.TradeRecordEntity
import com.locogo.astockguard.databinding.ActivityThsTradeSyncBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ThsTradeSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivityThsTradeSyncBinding

    private val openTradeFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importTradeFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThsTradeSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.btnOpenAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.btnImport.setOnClickListener {
            openTradeFile.launch(arrayOf("text/csv", "text/tab-separated-values", "text/plain", "application/csv", "*/*"))
        }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isTradeAccessibilityEnabled()
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                appContainer.database.cacheDao().getTradeRecords().count { it.source.startsWith("THS_") }
            }
            binding.tvEnabled.text = if (enabled) "● 辅助功能已启用" else "● 辅助功能未启用"
            binding.tvCount.text = "已同步成交：$count 条"
            binding.tvStatus.text = if (enabled) {
                "只读同步已启用。请手动打开同花顺的当日成交、历史成交或交割单页面，AStockGuard 会解析可访问的成交字段。"
            } else {
                "只读同步未启用。请在系统辅助功能中开启 AStockGuard 同花顺成交只读同步。"
            }
        }
    }

    private fun importTradeFile(uri: Uri) = lifecycleScope.launch {
        binding.tvStatus.text = "正在读取交割单…"
        val text = runCatching {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(16 * 1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count <= 0) break
                        result.append(buffer, 0, count)
                        require(result.length <= 10_000_000) { "交割单文件过大" }
                    }
                    result.toString()
                } ?: error("无法读取文件")
            }
        }.getOrElse {
            binding.tvStatus.text = "读取失败：${it.message}"
            return@launch
        }
        val parsed = ThsTradeFileParser.parse(text)
        if (parsed.isEmpty()) {
            binding.tvStatus.text = "没有识别到成交记录。当前支持 CSV、TSV、TXT，表头需要包含代码、方向、价格和数量。"
            return@launch
        }
        val inserted = withContext(Dispatchers.IO) {
            val dao = appContainer.database.cacheDao()
            val existing = dao.getTradeRecords()
            var count = 0
            parsed.forEach { trade ->
                val duplicate = existing.any { row ->
                    row.code == trade.code && row.side.equals(trade.side, true) &&
                        row.quantity == trade.quantity && abs(row.price - trade.price) < 0.0001 &&
                        abs(row.tradeAt - trade.tradeAt) <= 90_000L
                }
                if (!duplicate) {
                    dao.insertTradeRecord(
                        TradeRecordEntity(
                            tradeAt = trade.tradeAt, code = trade.code, side = trade.side,
                            quantity = trade.quantity, price = trade.price, source = "THS_FILE",
                            note = "同花顺/券商交割单文件导入"
                        )
                    )
                    count++
                }
            }
            count
        }
        binding.tvStatus.text = "交割单解析 ${parsed.size} 条，本次新增 $inserted 条。成交点会显示在对应股票图表中。"
        refreshStatus()
    }

    private fun isTradeAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ThsTradeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
