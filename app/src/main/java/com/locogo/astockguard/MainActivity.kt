package com.locogo.astockguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locogo.astockguard.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var marketRepository: MarketRepository
    private val aiClient = AiClient()
    private var latestSnapshot: MonitorSnapshot? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val webAiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            val answer = result.data?.getStringExtra(ChatGptWebActivity.EXTRA_RESULT).orEmpty()
            if (answer.isNotBlank()) binding.tvAi.text = answer
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)
        marketRepository = MarketRepository(settings)
        askNotificationPermission()

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnRefresh.setOnClickListener { refreshOnce() }
        binding.btnAi.setOnClickListener { analyzeWithAi() }
        binding.btnStart.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START))
            binding.tvMonitorState.text = "监控：已启动（腾讯免费行情）"
        }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
            binding.tvMonitorState.text = "监控：已停止"
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MonitorBus.snapshot.collect { it?.let(::render) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvMonitorState.text = "腾讯行情 / 当前设置仓位：${"%.1f".format(settings.positionRatio)}%"
    }

    private fun refreshOnce() = lifecycleScope.launch {
        binding.btnRefresh.isEnabled = false
        try { render(marketRepository.refresh()) }
        catch (t: Throwable) { binding.tvMarketState.text = "刷新失败：${t.message}" }
        finally { binding.btnRefresh.isEnabled = true }
    }

    private fun analyzeWithAi() {
        val snapshot = latestSnapshot ?: run { toast("请先刷新行情"); return }
        val prompt = PromptBuilder.build(snapshot, settings.positions(), binding.etQuestion.text.toString())
        if (settings.primaryType == "CHATGPT_WEB") {
            binding.tvAi.text = "正在调用 ChatGPT Web：登录有效时会自动写入、发送并把回复回传到这里；只有登录/页面异常时才需要手动处理。"
            webAiLauncher.launch(
                Intent(this, ChatGptWebActivity::class.java)
                    .putExtra(ChatGptWebActivity.EXTRA_PROMPT, prompt)
            )
            return
        }
        lifecycleScope.launch {
            binding.btnAi.isEnabled = false
            binding.tvAi.text = "AI分析中..."
            try {
                binding.tvAi.text = aiClient.analyze(settings.primaryProvider(), settings.backupProvider(), prompt)
            } catch (t: Throwable) { binding.tvAi.text = "AI调用失败：${t.message}" }
            finally { binding.btnAi.isEnabled = true }
        }
    }

    private fun render(snapshot: MonitorSnapshot) {
        latestSnapshot = snapshot
        val a = snapshot.assessment
        binding.tvMarketState.text = buildString {
            appendLine("更新时间：${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(snapshot.updatedAt))}")
            appendLine("风险/周期：${a.eventRisk} / ${a.marketPhase}")
            appendLine("观察池平均：${"%+.2f".format(a.avgChange)}%")
            appendLine("下跌占比：${"%.0f".format(a.redRatio * 100)}%  <-7%：${"%.0f".format(a.severeDropRatio * 100)}%")
            appendLine("建议仓位上限：${"%.0f".format(a.maxPositionRatio * 100)}%  当前：${"%.1f".format(snapshot.positionRatio)}%")
            append(a.advice)
        }
        val pos = settings.positions().associateBy { it.code }
        binding.tvQuotes.text = snapshot.quotes.joinToString("\n\n") { q ->
            val p = pos[q.code]; val sig = a.signals.firstOrNull { it.code == q.code }
            buildString {
                append("${q.code} ${q.name}"); if (p != null) append("  ${p.role}"); appendLine()
                appendLine("现价 ${q.latest ?: "?"}  涨跌 ${q.changeRatio?.let { "%+.2f%%".format(it) } ?: "?"}  VWAP ${q.vwap?.let { "%.2f".format(it) } ?: "?"}")
                appendLine("O ${q.open ?: "?"} H ${q.high ?: "?"} L ${q.low ?: "?"}  MA5 ${q.ma5?.let { "%.2f".format(it) } ?: "?"} MA10 ${q.ma10?.let { "%.2f".format(it) } ?: "?"}")
                append("R2 ${q.r2Grade}/${q.r2Score}")
                if (q.r2Score > 0) append("  ${q.r2Reason}")
                if (sig != null) append("\n${sig.level} ${sig.action}: ${sig.reason}")
            }
        }.ifBlank { "未返回行情，请检查代码或网络。" }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
