package com.locogo.astockguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locogo.astockguard.ui.main.DashboardScreen
import com.locogo.astockguard.ui.main.MainEffect
import com.locogo.astockguard.ui.main.MainViewModel
import com.locogo.astockguard.ui.theme.AStockGuardTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
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
        settings = appContainer.settings
        askNotificationPermission()
        hiddenChatGpt = HiddenChatGptSession(
            activity = this,
            settingsRepo = settings,
            onStatus = viewModel::onAiStatus,
            onCompleted = viewModel::onAiAnswer,
            onRequiresManual = ::showManualAiDialog
        )

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            AStockGuardTheme {
                DashboardScreen(
                    state = state,
                    positions = settings.positions(),
                    onRefresh = viewModel::refresh,
                    onAnalyze = viewModel::analyze,
                    onStartMonitor = ::startMonitor,
                    onStopMonitor = ::stopMonitor,
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.effects.collect { effect ->
                        if (effect is MainEffect.RunHiddenWebAi) hiddenChatGpt.analyze(effect.prompt)
                    }
                }
                launch { MonitorBus.snapshot.collect { it?.let(viewModel::acceptSnapshot) } }
            }
        }
        viewModel.refresh()
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(this, Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START))
        toast("实时监控已启动")
    }

    private fun stopMonitor() {
        startService(Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
        toast("实时监控已停止")
    }

    private fun showManualAiDialog(prompt: String, reason: String) {
        pendingManualPrompt = prompt
        viewModel.onAiFailed(reason)
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("AI网页登录需要处理")
            .setMessage("$reason\n\n正常分析不会跳转网页；仅登录、验证码或 DOM 异常时打开。")
            .setNegativeButton("稍后", null)
            .setPositiveButton("打开登录页面") { _, _ ->
                webAiLauncher.launch(Intent(this, ChatGptWebActivity::class.java).putExtra(ChatGptWebActivity.EXTRA_PROMPT, prompt))
            }
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        hiddenChatGpt.destroy()
        super.onDestroy()
    }
}
