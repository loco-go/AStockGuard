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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    private val importViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ThsPositionImportViewModel(appContainer.thsPositionImportRepository) as T
        })[ThsPositionImportViewModel::class.java]
    }

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
        binding.btnConfirmPositions.setOnClickListener { importViewModel.confirm() }
        binding.btnClearCandidates.setOnClickListener { importViewModel.clear() }
        binding.btnConfirmAccount.setOnClickListener { importViewModel.confirmAccount() }
        binding.btnClearImportedAccount.setOnClickListener {
            AlertDialog.Builder(this).setMessage("移除已导入的账户快照并恢复首页本地计算？持仓和成交流水会保留。")
                .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ -> importViewModel.clearImportedAccount() }.show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { importViewModel.rows.collect(::renderCandidates) }
                launch { importViewModel.message.collect { binding.tvImportResult.text = it } }
                launch { importViewModel.accountRows.collect(::renderAccountCandidates) }
                launch { importViewModel.accountMessage.collect { binding.tvAccountImportResult.text = it } }
            }
        }
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
                    "最近检测 $time：$diagnostic\n\n请停留在同花顺持仓页约 1 秒；列表较长时可缓慢滚动，返回此页勾选候选并确认导入。"
                } else {
                    "只读同步已启用。请手动打开同花顺持仓、当日成交或交割单页面，并停留约 1 秒等待识别。"
                }
            } else {
                "只读同步未启用。请在系统辅助功能中开启“股衡同花顺只读同步”，返回后再打开同花顺持仓页。"
            }
        }
    }

    private fun renderAccountCandidates(rows: List<ThsAccountImportRow>) {
        binding.accountCandidateList.removeAllViews()
        binding.btnConfirmAccount.isEnabled = rows.any { it.selected }
        rows.forEach { row ->
            binding.accountCandidateList.addView(CheckBox(this).apply {
                text = buildString {
                    append("${row.metric.label}：${row.candidate?.let { accountAmountInput(it.value) } ?: "未识别"} ${row.metric.unit}")
                    if (row.existing) append("（已导入）")
                    row.candidate?.let {
                        append("\n${if (it.source == "THS_MANUAL") "手工核对" else "采集时间"}：")
                        append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(it.observedAt)))
                    }
                }
                isChecked = row.selected
                setOnCheckedChangeListener { _, checked -> importViewModel.selectAccount(row.metric, checked) }
            })
            binding.accountCandidateList.addView(Button(this).apply {
                text = "核对/补全${row.metric.label}"
                setOnClickListener {
                    val input = EditText(this@ThsTradeSyncActivity).apply {
                        hint = "${row.metric.label}（${row.metric.unit}）"
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        setText(accountAmountInput(row.candidate?.value))
                    }
                    val dialog = AlertDialog.Builder(this@ThsTradeSyncActivity).setTitle("${row.metric.label}（${row.metric.unit}）")
                        .setView(input).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val value = input.text.toString().toDoubleOrNull()
                            if (value == null || !row.metric.valid(value)) input.error = "请输入有效数值；仓位为 0–100，总资产不能为负"
                            else { importViewModel.editAccount(row.metric, value); dialog.dismiss() }
                        }
                    }
                    dialog.show()
                }
            })
        }
    }

    private fun renderCandidates(rows: List<ThsImportRow>) {
        binding.tvCandidateEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.btnConfirmPositions.isEnabled = rows.any { it.selected }
        binding.btnConfirmPositions.text = "确认导入所选股票（${rows.count { it.selected }}）"
        binding.candidateList.removeAllViews()
        rows.forEach { row ->
            val candidate = row.candidate
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val check = CheckBox(this).apply {
                text = buildString {
                    append(candidate.name)
                    append("  ${candidate.code ?: "代码待补全"}")
                    if (row.existing) append("  已存在")
                    candidate.position?.let {
                        append("\n持仓 ${it.shares} 股  成本 ${it.cost}  可卖 ${it.availableShares ?: "未知"}")
                    } ?: append(if (row.existing) "\n未识别到完整数量/成本，确认时保留本地值" else "\n请核对/补全持仓字段")
                }
                isChecked = row.selected
                setOnCheckedChangeListener { _, checked -> importViewModel.select(candidate.name, checked) }
            }
            container.addView(check)
            container.addView(Button(this).apply {
                text = "核对/补全"
                contentDescription = "核对或补全${candidate.name}"
                setOnClickListener { editCandidate(candidate) }
            })
            binding.candidateList.addView(container)
        }
    }

    private fun editCandidate(candidate: ThsPositionCandidate) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 12)
        }
        fun field(label: String, value: String, type: Int): EditText = EditText(this).apply {
            hint = label
            inputType = type
            setText(value)
            form.addView(this)
        }
        val code = field("六位股票代码（可带交易所后缀）", candidate.code.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val shares = field("持仓数量", candidate.position?.shares?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val cost = field("成本价", candidate.position?.cost?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val available = field("可卖数量（未知请留空）", candidate.position?.availableShares?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val dialog = AlertDialog.Builder(this).setTitle(candidate.name).setView(form)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = com.locogo.astockguard.SettingsRepository.normalizeCode(code.text.toString())
                val quantity = shares.text.toString().toIntOrNull()
                val price = cost.text.toString().toDoubleOrNull()
                val sellableText = available.text.toString().trim()
                val sellable = sellableText.toIntOrNull()
                when {
                    !Regex("[0368]\\d{5}\\.(SH|SZ|BJ)").matches(normalized) -> code.error = "请输入有效股票代码"
                    quantity == null || quantity <= 0 -> shares.error = "请输入正整数股数"
                    price == null || !price.isFinite() || price <= 0 -> cost.error = "请输入有效成本价"
                    sellableText.isNotEmpty() && (sellable == null || sellable !in 0..quantity) -> available.error = "可卖数量须介于 0 和持仓数量之间"
                    else -> {
                        importViewModel.edit(candidate.name, ParsedThsPosition(normalized, candidate.name, quantity, price, availableShares = sellable))
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
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
