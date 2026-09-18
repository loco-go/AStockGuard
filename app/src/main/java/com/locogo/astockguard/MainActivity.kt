package com.locogo.astockguard

/*
 * 文件职责：承载主导航、Fragment 与一次性界面效果；业务状态由 ViewModel 管理，Activity 不直接调用行情客户端。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
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
import com.locogo.astockguard.integration.ths.ThsSyncBus
import com.locogo.astockguard.ui.main.MainEffect
import com.locogo.astockguard.ui.dashboard.DashboardViewModel
import com.locogo.astockguard.ui.dashboard.DashboardFragment
import com.locogo.astockguard.ui.main.MainSectionFragment
import com.locogo.astockguard.ui.main.ReferenceChartViewModel
import com.locogo.astockguard.ui.main.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var hiddenChatGpt: HiddenChatGptSession
    private var pendingManualPrompt: String = ""
    private var handledThsSyncAt: Long = 0L
    private var selectedDestination = R.id.nav_home
    val referenceCharts: ReferenceChartViewModel by viewModels {
        ReferenceChartViewModel.Factory(appContainer.marketRepository)
    }
    private val homeBack = object : OnBackPressedCallback(false) {
        /** 从其他主页面返回首页，详情的返回仍交给 Fragment 返回栈。 */
        override fun handleOnBackPressed() {
            binding.mainNavigation.selectedItemId = R.id.nav_home
        }
    }

    val dashboardViewModel: DashboardViewModel by viewModels {
        val c = appContainer
        MainViewModel.Factory(
            c.settings, c.marketRepository, c.fundFlowRepository, c.newsRepository,
            c.level2Repository, c.paperTradingRepository, c.replayEngine, c.r2Scanner,
            c.reviewRepository, c.alertHistoryRepository, c.aiClient, c.database.cacheDao()
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

    /** 初始化唯一的业务事件订阅和主导航，并恢复系统保存的页面。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.mainRoot)
        setupNavigation(savedInstanceState)
        settings = appContainer.settings
        handledThsSyncAt = settings.thsLastSyncAt
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
                // 同花顺辅助服务写入持仓后，立即刷新首页，避免仍显示旧配置。
                launch {
                    ThsSyncBus.events.collect {
                        handledThsSyncAt = settings.thsLastSyncAt
                        dashboardViewModel.syncPortfolioSettings()
                        dashboardViewModel.refresh()
                    }
                }
            }
        }
        if (savedInstanceState == null) dashboardViewModel.refresh()
    }

    /** 返回前台时核对持仓和导入时间，补偿后台期间遗漏的同步事件。 */
    override fun onResume() {
        super.onResume()
        // 设置页也能修改持仓；每次回到首页都先同步本地真值，不依赖网络刷新成功与否。
        val portfolioChanged = if (::settings.isInitialized) dashboardViewModel.syncPortfolioSettings() else false
        // 用户查看同花顺时首页处于 STOPPED，内存事件可能无人接收，因此返回时再核对持久化时间戳。
        val thsChanged = ::settings.isInitialized && settings.thsLastSyncAt > handledThsSyncAt
        if (thsChanged) {
            handledThsSyncAt = settings.thsLastSyncAt
        }
        if (portfolioChanged || thsChanged) {
            dashboardViewModel.refresh()
        }
    }

    /** 启动前台行情监控服务，并立即反馈启动状态。 */
    fun startMonitor() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START)
        )
        MonitorBus.updateRunning(true)
        toast("实时监控已启动")
    }

    /** 请求停止行情监控服务并更新界面状态。 */
    fun stopMonitor() {
        startService(Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
        MonitorBus.updateRunning(false)
        toast("实时监控已停止")
    }

    /** 打开已有设置页面，返回后的配置同步由 onResume 处理。 */
    fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** 建立五项导航；重复点击保持当前页面，不重复执行任何业务请求。 */
    private fun setupNavigation(savedState: Bundle?) {
        binding.mainNavigation.isItemActiveIndicatorEnabled = false
        selectedDestination = savedState?.getInt("main_destination", R.id.nav_home) ?: R.id.nav_home
        onBackPressedDispatcher.addCallback(this, homeBack)
        binding.mainNavigation.setOnItemSelectedListener { item ->
            if (supportFragmentManager.isStateSaved || supportFragmentManager.backStackEntryCount > 0) {
                false
            } else {
                selectDestination(item.itemId)
                true
            }
        }
        binding.mainNavigation.setOnItemReselectedListener { item ->
            if (!supportFragmentManager.isStateSaved && supportFragmentManager.backStackEntryCount == 0) {
                (supportFragmentManager.findFragmentByTag("main:${item.itemId}") as? MainSectionFragment)?.showRootPage()
            }
        }
        supportFragmentManager.addOnBackStackChangedListener(::updateNavigationVisibility)
        if (supportFragmentManager.findFragmentByTag("main:$selectedDestination") == null &&
            supportFragmentManager.backStackEntryCount == 0) selectDestination(selectedDestination)
        binding.mainNavigation.menu.findItem(selectedDestination).isChecked = true
        updateNavigationVisibility()
    }

    /** 复用各主页面实例，隐藏页降到 CREATED，避免重复收集状态和重置滚动位置。 */
    private fun selectDestination(destination: Int) {
        val manager = supportFragmentManager
        val tag = "main:$destination"
        val target = manager.findFragmentByTag(tag) ?: MainSectionFragment.newInstance(destination)
        manager.beginTransaction().apply {
            manager.fragments.filter { it.isAdded && it !== target }.forEach {
                hide(it)
                setMaxLifecycle(it, Lifecycle.State.CREATED)
            }
            if (target.isAdded) show(target) else add(R.id.mainContainer, target, tag)
            setMaxLifecycle(target, Lifecycle.State.RESUMED)
            setPrimaryNavigationFragment(target)
        }.commitNow()
        selectedDestination = destination
        updateNavigationVisibility()
    }

    /** 详情显示期间隐藏主导航，返回原页面后恢复导航和首页返回行为。 */
    private fun updateNavigationVisibility() {
        val atRoot = supportFragmentManager.backStackEntryCount == 0
        binding.mainNavigation.visibility = if (atRoot) View.VISIBLE else View.GONE
        homeBack.isEnabled = atRoot && selectedDestination != R.id.nav_home
    }

    /** 让顶部栏目与底部主导航保持一致，同一主页面内切换热点、情绪等子栏目。 */
    fun showReferencePage(page: String, query: String? = null) {
        val destination = when (page) {
            "market", "hotspots", "sentiment" -> R.id.nav_market
            "watchlist" -> R.id.nav_watchlist
            "account" -> R.id.nav_account
            "profile" -> R.id.nav_profile
            else -> R.id.nav_home
        }
        if (binding.mainNavigation.selectedItemId != destination) binding.mainNavigation.selectedItemId = destination
        (supportFragmentManager.findFragmentByTag("main:$destination") as? MainSectionFragment)?.showPage(page, query)
    }

    /** 保存所选导航项；页面内容及详情返回栈交由 FragmentManager 保存。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("main_destination", selectedDestination)
        super.onSaveInstanceState(outState)
    }

    /** 仅在网页 AI 需要登录或人工恢复时显示入口，保留待处理问题。 */
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

    /** 在 Android 13 及以上请求运行监控通知所需的权限。 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 展示不阻塞操作的短提示。 */
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** 销毁隐藏网页会话，释放 Activity 持有的资源。 */
    override fun onDestroy() {
        hiddenChatGpt.destroy()
        super.onDestroy()
    }
}
