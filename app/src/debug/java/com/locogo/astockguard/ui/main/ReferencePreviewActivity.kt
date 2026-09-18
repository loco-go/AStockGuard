package com.locogo.astockguard.ui.main

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.locogo.astockguard.*
import com.locogo.astockguard.data.ai.AiStrategy
import com.locogo.astockguard.data.ai.AiStockStrategy
import com.locogo.astockguard.data.fundflow.SectorFundFlow
import com.locogo.astockguard.data.fundflow.SectorFundFlowResult
import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import com.locogo.astockguard.data.news.NewsItem
import com.locogo.astockguard.data.news.NewsRiskAssessment
import com.locogo.astockguard.databinding.ActivityMainBinding
import com.locogo.astockguard.databinding.FragmentMainSectionBinding
import com.locogo.astockguard.domain.plan.PositionCategory
import com.locogo.astockguard.domain.plan.PositionNextDayPlan
import java.time.Instant

/** 只用于设计对照的离线样例页；不请求网络、不创建 Repository、不写入用户数据。 */
class ReferencePreviewActivity : AppCompatActivity() {
    /** 使用生产 XML 和生产渲染器展示固定样例，支持从 adb 选择待截图页面。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val main = ActivityMainBinding.inflate(layoutInflater)
        setContentView(main.root)
        applySystemBarInsets(main.mainRoot)
        val binding = FragmentMainSectionBinding.inflate(layoutInflater, main.mainRoot, false)
        val contentParams = main.mainContainer.layoutParams
        main.mainRoot.removeView(main.mainContainer)
        main.mainRoot.addView(binding.root, 0, contentParams)
        val page = intent.getStringExtra("page") ?: "home"
        val layout = when (page) {
            "account" -> R.layout.view_reference_account
            "hotspots" -> R.layout.view_reference_hotspots
            "sentiment" -> R.layout.view_reference_sentiment
            "strategy" -> R.layout.view_reference_strategy
            else -> R.layout.view_reference_home
        }
        val content = layoutInflater.inflate(layout, binding.sectionContent, true)
        ReferencePageRenderer(content) {}.render(fixtureState(), fixtureCharts())
        binding.sectionStatus.text = "设计对照样例 · 非真实行情 / 账户"
        val red = page in listOf("home", "account")
        binding.topTabs.setBackgroundColor(if (red) 0xFFD92930.toInt() else 0xFFFFFFFF.toInt())
        listOf("首页" to "home", "自选" to "watchlist", "行情" to "market", "热点" to "hotspots", "策略" to "strategy", "账户" to "account").forEach { (title, key) ->
            val label = TextView(this).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setTextColor(if (red) 0xFFFFFFFF.toInt() else if (page == key || page == "sentiment" && key == "market") 0xFFEA2635.toInt() else 0xFF737780.toInt())
                if (page == key) setTypeface(typeface, Typeface.BOLD)
            }
            binding.topTabs.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        }
        main.mainNavigation.isItemActiveIndicatorEnabled = false
        main.mainNavigation.selectedItemId = when (page) { "account" -> R.id.nav_account; "hotspots", "sentiment" -> R.id.nav_market; else -> R.id.nav_home }
        if (intent.getBooleanExtra("empty", false)) ReferencePageRenderer(content) {}.render(MainUiState(), ReferenceVisualData())
    }

    /** 调试入口收到新页面参数时重建预览，避免截图仍停留在上一页。 */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    /** 构造与参考图同数量级的固定数据，所有样例仅存在于 debug 源集。 */
    private fun fixtureState(): MainUiState {
        val now = Instant.parse("2026-09-17T06:27:00Z").toEpochMilli()
        val positions = listOf(Position("600519.SH", "贵州茅台", 100, 1561.20, "CORE", 100), Position("300750.SZ", "宁德时代", 500, 171.26, "CORE", 500), Position("601318.SH", "中国平安", 1000, 43.92, "CORE", 1000))
        val stocks = listOf(Quote("600519.SH", "贵州茅台", "14:27:00", latest = 1723.60, previousClose = 1710.0, changeRatio = 0.80), Quote("300750.SZ", "宁德时代", "14:27:00", latest = 183.20, previousClose = 181.0, changeRatio = 1.22), Quote("601318.SH", "中国平安", "14:27:00", latest = 45.78, previousClose = 45.2, changeRatio = 1.28))
        val leaders = listOf(Quote("603019.SH", "中科曙光", latest = 56.20, changeRatio = 10.0), Quote("002085.SZ", "万丰奥威", latest = 18.46, changeRatio = 9.99), Quote("688256.SH", "寒武纪-U", latest = 241.10, changeRatio = 6.53), Quote("688981.SH", "中芯国际", latest = 98.30, changeRatio = 4.78), Quote("002625.SZ", "光启技术", latest = 40.12, changeRatio = 7.46))
        val indices = listOf(Quote("000001.SH", "上证指数", latest = 3287.43, previousClose = 3274.87, changeRatio = 0.38), Quote("399001.SZ", "深证成指", latest = 10456.78, previousClose = 10388.57, changeRatio = 0.66), Quote("399006.SZ", "创业板指", latest = 2173.36, previousClose = 2155.41, changeRatio = 0.83))
        val sectors = listOf("人工智能", "低空经济", "半导体", "消费电子", "机器人").mapIndexed { index, name ->
            SectorFundFlow("BK00$index", name, "INDUSTRY", listOf(3.62, 2.84, 2.17, 1.72, 1.65)[index], (9.23 - index) * 100_000_000, 3.2, 0.0, 0.0, 0.0, 0.0, leaders[index].name, leaders[index].code)
        }
        return MainUiState(
            snapshot = MonitorSnapshot(now, stocks + leaders, indices, MarketAssessment("E1", "M2", 0.7, 1.2, 0.3, 0.0, "赚钱效应较好，关注高位分歧", emptyList()), 90.7),
            positions = positions, monitoredCodes = (stocks + leaders).map { it.code }, cashBalance = 48320.26,
            selectedCode = "上证指数 · 样例",
            dailyBars = (0..23).map { i -> val close = 3020.0 + i * 3 + listOf(-3.0, 2.0, -1.0, 4.0)[i % 4]; DailyBar("2026-09-${i + 1}", close - if (i % 3 == 0) -2 else 3, close, close + 4, close - 5, 1000.0 + (i % 5) * 300) },
            importedAccountMetrics = listOf(ImportedAccountMetricEntity("TOTAL_ASSETS", 518320.26, now, now, "THS_MANUAL"), ImportedAccountMetricEntity("TODAY_PNL", 6280.15, now, now, "THS_MANUAL"), ImportedAccountMetricEntity("POSITION_PCT", 90.7, now, now, "THS_MANUAL")),
            sectorFundFlow = SectorFundFlowResult("INDUSTRY", sectors, "设计样例", false),
            newsRisk = NewsRiskAssessment("E1", 3, listOf(NewsItem("preview", "设计样例", "短期需关注外围波动与量能变化", "", now, now), NewsItem("preview2", "设计样例", "部分高位题材股波动加剧，注意追高风险", "", now, now)), 1, false, now),
            aiStrategy = AiStrategy("HOLD", 85, 70, "MEDIUM", listOf(AiStockStrategy("688981.SH", "HOLD", 85, 20, 100, "等待回踩", "失守支撑", "当前处于上升周期，关注板块轮动与风险控制")), ""),
            positionPlans = positions.map { PositionNextDayPlan(it.code, it.name, PositionCategory.CORE, "持", 100, it.cost, "持有，关注压力与回补机会") }
        )
    }

    /** 固定走势只用于截图比较，不通过生产 ViewModel 或缓存写入账户。 */
    private fun fixtureCharts(): ReferenceVisualData {
        val shape = listOf(0.0, 2.0, 1.0, 5.0, 4.0, 7.0, 5.0, 9.0, 6.0, 8.0, 6.0, 11.0, 13.0, 12.0, 15.0, 12.0, 14.0, 11.0, 13.0, 12.0, 16.0, 15.0, 19.0, 21.0)
        return ReferenceVisualData(
            indices = listOf("000001.SH", "399001.SZ", "399006.SZ").associateWith { ReferenceTrend(shape, listOf("09:30", "14:27"), "09:30 — 14:27") },
            sentiment = 72.0, upHistory = listOf(3500.0, 3000.0, 3800.0, 3550.0, 2900.0, 3600.0, 3850.0), downHistory = listOf(1650.0, 2000.0, 1400.0, 1600.0, 2100.0, 1700.0, 1500.0),
            streakHistory = listOf(3.0, 4.0, 4.0, 5.0, 4.0, 6.0, 6.0), historyDates = listOf("04-17", "04-25"),
            accountReturns = listOf(0.0, -0.5, 0.1, -0.3, 0.5, -0.2, 1.0, 3.5, 2.8, 4.5, 3.3, 2.4, 2.2, 3.1, 5.0, 5.5, 4.4, 4.8, 4.9, 6.2, 5.3, 6.0, 5.8, 4.9, 5.4, 6.8, 5.9, 6.4),
            benchmarkReturns = listOf(0.0, -1.0, -1.3, -1.4, -1.0, -1.1, -0.8, 0.1, -0.5, 0.9, 1.1, 0.2, 0.0, 0.7, 1.5, 1.3, 1.9, 1.8, 2.0, 2.5, 2.3, 2.8, 3.0, 2.7, 3.4, 3.6, 3.2, 3.1), returnDates = listOf("03-01", "03-29")
        )
    }

    /** 将调试页控件尺寸转换为屏幕像素。 */
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
