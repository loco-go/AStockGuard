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
import com.locogo.astockguard.ui.dashboard.DashboardViewModel
import com.locogo.astockguard.ui.main.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var hiddenChatGpt: HiddenChatGptSession
    private var pendingManualPrompt: String = ""

    val dashboardViewModel: DashboardViewModel by viewModels {
        val c = appContainer
        MainViewModel.Factory(
            c.settings, c.marketRepository, c.fundFlowRepository, c.newsRepository,
            c.level2Repository, c.paperTradingRepository, c.replayEngine, c.r2Scanner,
            c.reviewRepository, c.aiClient, c.database.cacheDao()
        )
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("通知权限未开启，监控仍可运行但不会推送交易提示")
    }
    private val webAiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        val prompt = pendingManualPrompt
        if (result.resultCode == RESULT_OK) {
            val answer = result.data?.getStringExtra(ChatGptWebActivity.EXTRA_RESULT).orEmpty()
            if (answer.isNotBlank()) {
                dashboardViewModel.onAiAnswer(prompt, answer)
                pendingManualPrompt = ""
                return@registerForActivityResult
            }
        }
        if (prompt.isNotBlank()) {
            dashboardViewModel.onAiStatus("网页登录已关闭，重新尝试后台分析。")
            hiddenChatGpt.analyze(prompt)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.mainContainer)
        settings = appContainer.settings
        askNotificationPermission()
        hiddenChatGpt = HiddenChatGptSession(
            this, settings, dashboardViewModel::onAiStatus,
            dashboardViewModel::onAiAnswer, ::showManualAiDialog
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dashboardViewModel.effects.collect { effect ->
                        if (effect is MainEffect.RunHiddenWebAi) hiddenChatGpt.analyze(effect.prompt)
                    }
                }
                launch { MonitorBus.snapshot.collect { it?.let(dashboardViewModel::acceptSnapshot) } }
                launch { MonitorBus.running.collect(dashboardViewModel::updateMonitorRunning) }
            }
        }
        if (savedInstanceState == null) dashboardViewModel.refresh()
    }

    fun startMonitor() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START)
        )
        MonitorBus.updateRunning(true)
        toast("实时监控已启动")
    }

    fun stopMonitor() {
        startService(Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
        MonitorBus.updateRunning(false)
        toast("实时监控已停止")
    }

    fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun showManualAiDialog(prompt: String, reason: String) {
        pendingManualPrompt = prompt
        dashboardViewModel.onAiFailed(reason)
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("AI 网页登录需要处理")
            .setMessage("$reason\n\n正常分析不会跳转网页；仅登录、验证码或 DOM 异常时打开。")
            .setNegativeButton("稍后", null)
            .setPositiveButton("打开登录页面") { _, _ ->
                webAiLauncher.launch(
                    Intent(this, ChatGptWebActivity::class.java)
                        .putExtra(ChatGptWebActivity.EXTRA_PROMPT, prompt)
                )
            }
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        hiddenChatGpt.destroy()
        super.onDestroy()
    }
}
