package com.locogo.astockguard.integration.ths

/*
 * 文件职责：展示同花顺只读同步状态并承接用户主动选择的交割单文件；Activity 不负责解析规则，也不执行任何券商页面操作。
 * 架构边界：集成层只读取用户授权的数据，不保存整页原文，不执行真实交易。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        lifecycleScope.launch { ThsSyncBus.events.collect { refreshStatus() } }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isTradeAccessibilityEnabled()
        lifecycleScope.launch {
            val tradeCount = withContext(Dispatchers.IO) {
                appContainer.database.cacheDao().getTradeRecords().count { it.source.startsWith("THS_") }
            }
            val settings = appContainer.settings
            val positionCount = settings.positions().size
            val lastSync = settings.thsLastSyncAt
            val diagnostic = settings.thsLastSyncMessage
            binding.tvEnabled.text = if (enabled) "● 辅助功能已启用" else "● 辅助功能未启用"
            binding.tvCount.text = "当前持仓：$positionCount 只　已同步成交：$tradeCount 条"
            binding.tvStatus.text = if (enabled) {
                if (lastSync > 0L && diagnostic.isNotBlank()) {
                    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastSync))
                    "最近检测 $time：$diagnostic\n\n请停留在同花顺持仓页约 1 秒；列表较长时可缓慢滚动，股衡会合并当前可见持仓。"
                } else {
                    "只读同步已启用。请手动打开同花顺持仓、当日成交或交割单页面，并停留约 1 秒等待识别。"
                }
            } else {
                "只读同步未启用。请在系统辅助功能中开启“股衡同花顺只读同步”，返回后再打开同花顺持仓页。"
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
