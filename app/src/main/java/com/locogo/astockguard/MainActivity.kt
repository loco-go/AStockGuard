package com.locogo.astockguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locogo.astockguard.databinding.ActivityMainBinding
import com.locogo.astockguard.ui.main.MainEffect
import com.locogo.astockguard.ui.main.MainUiState
import com.locogo.astockguard.ui.main.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var hiddenChatGpt: HiddenChatGptSession
    private var pendingManualPrompt: String = ""

    private val viewModel: MainViewModel by viewModels {
        val c = appContainer
        MainViewModel.Factory(c.settings, c.marketRepository, c.aiClient, c.database.cacheDao())
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val webAiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        val prompt = pendingManualPrompt
        if (result.resultCode == RESULT_OK) {
            val answer = result.data?.getStringExtra(ChatGptWebActivity.EXTRA_RESULT).orEmpty()
            if (answer.isNotBlank()) {
                viewModel.onAiAnswer(prompt, answer)
                pendingManualPrompt = ""
                return@registerForActivityResult
            }
        }
        if (prompt.isNotBlank()) {
            viewModel.onAiStatus("网页登录已关闭，重新尝试后台分析…")
            hiddenChatGpt.analyze(prompt)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = appContainer.settings
        askNotificationPermission()

        hiddenChatGpt = HiddenChatGptSession(
            activity = this,
            settingsRepo = settings,
            onStatus = viewModel::onAiStatus,
            onCompleted = viewModel::onAiAnswer,
            onRequiresManual = ::showManualAiDialog
        )

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnAi.setOnClickListener { viewModel.analyze(binding.etQuestion.text.toString()) }
        binding.btnStart.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START)
            )
            binding.tvMonitorState.text = "监控：已启动（腾讯免费行情 + Room缓存）"
        }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
            binding.tvMonitorState.text = "监控：已停止"
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::renderState) }
                launch {
                    viewModel.effects.collect { effect ->
                        when (effect) {
                            is MainEffect.RunHiddenWebAi -> hiddenChatGpt.analyze(effect.prompt)
                        }
                    }
                }
                launch { MonitorBus.snapshot.collect { snapshot -> snapshot?.let(viewModel::acceptSnapshot) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvMonitorState.text = "腾讯行情 / Room缓存 / 当前设置仓位：${"%.1f".format(settings.positionRatio)}%"
    }

    private fun renderState(state: MainUiState) {
        binding.btnRefresh.isEnabled = !state.loading
        binding.btnAi.isEnabled = !state.aiLoading
        binding.tvAi.text = state.aiText
        state.snapshot?.let(::renderSnapshot)
        state.error?.let {
            toast(it)
            viewModel.consumeError()
        }
    }

    private fun renderSnapshot(snapshot: MonitorSnapshot) {
        val a = snapshot.assessment
        binding.tvMarketState.text = buildString {
            appendLine("更新时间：${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(snapshot.updatedAt))}")
            appendLine("数据源：${snapshot.dataHealth.source}${if (snapshot.dataHealth.isStale) "（缓存/禁止实时交易动作）" else "（实时）"}")
            if (snapshot.dataHealth.message.isNotBlank()) appendLine(snapshot.dataHealth.message)
            appendLine("风险/周期：${a.eventRisk} / ${a.marketPhase}")
            appendLine("观察池平均：${"%+.2f".format(a.avgChange)}%")
            appendLine("下跌占比：${"%.0f".format(a.redRatio * 100)}%  <-7%：${"%.0f".format(a.severeDropRatio * 100)}%")
            appendLine("建议仓位上限：${"%.0f".format(a.maxPositionRatio * 100)}%  当前：${"%.1f".format(snapshot.positionRatio)}%")
            append(a.advice)
        }
        val pos = settings.positions().associateBy { it.code }
        binding.tvQuotes.text = snapshot.quotes.joinToString("\n\n") { q ->
            val p = pos[q.code]
            val sig = a.signals.firstOrNull { it.code == q.code }
            buildString {
                append("${q.code} ${q.name}")
                if (p != null) append("  ${p.role}")
                appendLine()
                appendLine("现价 ${q.latest ?: "?"}  涨跌 ${q.changeRatio?.let { "%+.2f%%".format(it) } ?: "?"}  VWAP ${q.vwap?.let { "%.2f".format(it) } ?: "?"}")
                appendLine("O ${q.open ?: "?"} H ${q.high ?: "?"} L ${q.low ?: "?"}  MA5 ${q.ma5?.let { "%.2f".format(it) } ?: "?"} MA10 ${q.ma10?.let { "%.2f".format(it) } ?: "?"}")
                append("R2 ${q.r2Grade}/${q.r2Score}")
                if (q.r2Score > 0) append("  ${q.r2Reason}")
                if (sig != null) append("\n${sig.level} ${sig.action}: ${sig.reason}")
            }
        }.ifBlank { "未返回行情，请检查代码或网络。" }
    }

    private fun showManualAiDialog(prompt: String, reason: String) {
        pendingManualPrompt = prompt
        viewModel.onAiFailed(reason)
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("AI网页登录需要处理")
            .setMessage("$reason\n\n平时 AI 已改为隐藏 WebView 后台执行；只有登录失效、验证码或网页结构变化时才会打开网页。")
            .setNegativeButton("稍后") { _, _ -> }
            .setPositiveButton("打开登录页面") { _, _ ->
                webAiLauncher.launch(
                    Intent(this, ChatGptWebActivity::class.java)
                        .putExtra(ChatGptWebActivity.EXTRA_PROMPT, prompt)
                )
            }
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        hiddenChatGpt.destroy()
        super.onDestroy()
    }
}
