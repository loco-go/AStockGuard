package com.locogo.astockguard.ui.main

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.locogo.astockguard.Quote
import com.locogo.astockguard.R
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.integration.ths.ThsAccountMetric
import com.locogo.astockguard.ui.dashboard.DashboardSummaryMapper
import com.locogo.astockguard.ui.dashboard.ImportedAccountPresenter
import com.locogo.astockguard.designsystem.R as DesignR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 将共享业务状态填入参考图的独立 XML；生产页面与调试视觉验收使用同一渲染器。 */
class ReferencePageRenderer(private val root: View, private val action: (String) -> Unit) {
    private val context get() = root.context
    private val indexCodes = listOf("000001.SH", "399001.SZ", "399006.SZ")
    private val nameIds = listOf(R.id.indexName0, R.id.indexName1, R.id.indexName2)
    private val valueIds = listOf(R.id.indexValue0, R.id.indexValue1, R.id.indexValue2)
    private val changeIds = listOf(R.id.indexChange0, R.id.indexChange1, R.id.indexChange2)
    private val rangeIds = listOf(R.id.indexRange0, R.id.indexRange1, R.id.indexRange2)
    private val graphIds = listOf(R.id.indexGraph0, R.id.indexGraph1, R.id.indexGraph2)

    init {
        bindActions()
    }

    /** 更新当前布局存在的模块；不显示的页面不会订阅或触发业务任务。 */
    fun render(state: MainUiState, charts: ReferenceVisualData, hidden: Boolean = false, query: String = "") {
        renderIndices(state, charts)
        renderBreadth(state, charts)
        renderOpportunities(state)
        renderRisk(state)
        renderAccount(state, charts, hidden)
        renderHotspots(state)
        renderStrategy(state, hidden)
        renderWatchlist(state, query)
        renderProfile(state)
    }

    /** 绑定设计图中的文字入口，所有可点击控件均指向已实现页面或说明。 */
    private fun bindActions() {
        mapOf(
            R.id.marketMore to "market", R.id.marketRefresh to "refresh", R.id.sentimentMore to "sentiment",
            R.id.limitMore to "sentiment", R.id.opportunityMore to "hotspots", R.id.riskMore to "news",
            R.id.privacyToggle to "privacy", R.id.accountImport to "sync", R.id.tradeTools to "tools",
            R.id.strategyTools to "tools", R.id.planTools to "tools", R.id.analyzeAction to "ai",
            R.id.hotspotRefresh to "sector_refresh", R.id.manageWatchlist to "settings", R.id.accountMore to "account",
            R.id.refreshNews to "refresh_news", R.id.leaderInfo to "info:领涨股票来自板块资金数据，不等同于已验证的龙头股票。",
            R.id.exposureInfo to "info:仓位优先展示核对导入值；本地估算需要完整持仓报价和现金配置。",
            R.id.equityInfo to "info:收益曲线需要现金流与费用校正。当前未接入完整收益序列，不能用持仓市值冒充收益率。",
            R.id.sentimentInfo to "info:全市场情绪、连板及炸板统计尚未接入；当前监控池的涨跌数量单独标注。",
            R.id.breadthHistoryInfo to "info:暂无全市场涨跌家数历史，不生成模拟统计。",
            R.id.streakHistoryInfo to "info:暂无可核验的历史连板高度。"
        ).forEach { (id, route) -> root.findViewById<View>(id)?.setOnClickListener { action(route) } }
    }

    /** 渲染三列指数卡，分别保留现价、涨跌、真实趋势及采样/缓存来源。 */
    private fun renderIndices(state: MainUiState, charts: ReferenceVisualData) {
        if (root.findViewById<View>(R.id.indexGraph0) == null) return
        val quotes = state.snapshot?.marketIndices.orEmpty().associateBy { it.code }
        indexCodes.forEachIndexed { index, code ->
            val quote = quotes[code]
            val price = quote?.latest.validPrice()
            val delta = if (price != null) quote?.previousClose.validPrice()?.let { price - it } else null
            text(valueIds[index], price?.let(::decimal) ?: "--")
            text(changeIds[index], "${delta?.let(::signed) ?: "--"}  ${quote?.changeRatio?.takeIf { price != null }?.let(::percent) ?: "--"}")
            tone(valueIds[index], quote?.changeRatio)
            tone(changeIds[index], quote?.changeRatio)
            val trend = charts.indices[code]
            graph(graphIds[index])?.line(trend?.values.orEmpty(), positive = (quote?.changeRatio ?: 0.0) >= 0.0)
            text(rangeIds[index], trend?.source ?: "走势加载中")
            text(nameIds[index], quote?.name?.takeIf(String::isNotBlank) ?: listOf("上证指数", "深证成指", "创业板指")[index])
        }
        text(R.id.marketMore, state.snapshot?.let { "${time(it.updatedAt)} 更新 ›" } ?: "刷新 ›")
    }

    /** 仪表保留参考图结构，尚未接入的全市场指标不由监控池涨跌率冒充。 */
    private fun renderBreadth(state: MainUiState, charts: ReferenceVisualData) {
        val market = DashboardSummaryMapper.market(state.snapshot, state.monitoredCodes)
        val ready = state.snapshot != null
        text(R.id.breadthUp, if (ready) market.risingCount.toString() else "--")
        text(R.id.breadthFlat, if (ready) market.flatCount.toString() else "--")
        text(R.id.breadthDown, if (ready) market.fallingCount.toString() else "--")
        text(R.id.marketCoverage, "当前监控池 ${market.trackedCount} 只 · 有效报价 ${market.trackedCount - market.unknownCount} 只")
        val scoreText = charts.sentiment?.let { decimal(it, 0) } ?: "--"
        val scoreLabel = if (charts.sentiment != null) "市场情绪" else "待接入"
        graph(R.id.homeGauge)?.gauge(charts.sentiment, scoreText, scoreLabel)
        graph(R.id.sentimentGauge)?.gauge(charts.sentiment, scoreText, scoreLabel)
        text(R.id.homeSentimentNote, state.snapshot?.assessment?.advice?.take(22) ?: "全市场情绪待接入")
        text(R.id.sentimentBreadth, if (ready) "${market.risingCount}:${market.fallingCount}" else "--")
        text(R.id.sentimentCoverage, "涨跌家数范围：当前监控池 ${market.trackedCount} 只；全市场及历史指标尚未接入")
        graph(R.id.breadthHistory)?.bars(charts.upHistory, charts.downHistory, charts.historyDates, "暂无全市场历史统计")
        graph(R.id.streakHistory)?.line(charts.streakHistory, charts.historyDates, true, empty = "暂无历史连板统计")
    }

    /** 展示板块主力资金排行，净流入保留金额语义，不写作成交额或热度评分。 */
    private fun renderOpportunities(state: MainUiState) {
        val names = listOf(R.id.opportunityName0, R.id.opportunityName1, R.id.opportunityName2)
        val changes = listOf(R.id.opportunityChange0, R.id.opportunityChange1, R.id.opportunityChange2)
        val notes = listOf(R.id.opportunityNote0, R.id.opportunityNote1, R.id.opportunityNote2)
        val graphs = listOf(R.id.opportunityGraph0, R.id.opportunityGraph1, R.id.opportunityGraph2)
        val rows = state.sectorFundFlow?.rows.orEmpty().sortedByDescending { it.mainNet }.take(3)
        repeat(3) { index ->
            val sector = rows.getOrNull(index)
            text(names[index], sector?.name ?: "暂无板块")
            text(changes[index], sector?.changePct?.let(::percent) ?: "--")
            tone(changes[index], sector?.changePct)
            text(notes[index], sector?.let { "主力 ${compact(it.mainNet)}" } ?: "等待资金数据")
            graph(graphs[index])?.line(emptyList(), empty = if (sector == null) "暂无走势" else "历史走势待接入")
        }
        text(R.id.sectorSource, state.sectorFundFlow?.let { "主力资金排行 · ${if (it.stale) "缓存 · " else ""}${it.source}" } ?: "主力资金排行 · 暂无数据")
    }

    /** 风险卡使用已有市场评估与新闻证据，保留时间和缓存信息。 */
    private fun renderRisk(state: MainUiState) {
        val evidence = state.newsRisk.evidence
        text(R.id.riskHeadline, evidence.firstOrNull()?.title ?: state.snapshot?.assessment?.advice ?: "等待市场风险评估")
        text(R.id.riskDescription, evidence.firstOrNull()?.let { "${it.source} · ${time(it.publishedAt)}${if (state.newsRisk.stale) " · 缓存" else ""}" } ?: "行情与新闻更新后展示风险证据")
        text(R.id.riskSecondary, evidence.getOrNull(1)?.title ?: "按计划执行，关注仓位与量能变化")
        root.findViewById<LinearLayout>(R.id.newsRows)?.let { container ->
            container.removeAllViews()
            if (evidence.isEmpty()) empty(container, "暂无新闻风险证据")
            evidence.forEach { item ->
                val block = column().apply { setPadding(dp(0), dp(10), dp(0), dp(10)) }
                block.addView(label(item.title, 14f, bold = true))
                block.addView(label("${item.source} · ${time(item.publishedAt)}", 10f, DesignR.color.gh_secondary))
                container.addView(block)
                separator(container)
            }
        }
    }

    /** 将账户汇总放回参考图的资产头部、两列概览、环图与表格，缺价不补零。 */
    private fun renderAccount(state: MainUiState, charts: ReferenceVisualData, hidden: Boolean) {
        if (root.findViewById<View>(R.id.accountTotal) == null) return
        val summary = ReferenceAccountMapper.map(state)
        val value: (Double?) -> String = { if (hidden) "••••" else it?.let(::amount) ?: "--" }
        text(R.id.accountTotal, value(summary.total))
        text(R.id.accountToday, if (hidden) "••••" else summary.today?.let(::signedAmount) ?: summary.todayStatus)
        text(R.id.accountTodayLabel, summary.todayLabel)
        tone(R.id.accountToday, summary.today)
        text(R.id.accountMarketValue, value(summary.marketValue))
        text(R.id.accountCash, if (hidden) "••••" else "未提供")
        text(R.id.accountSource, summary.source)
        text(R.id.overviewValue, value(summary.marketValue))
        text(R.id.overviewPnl, if (hidden) "••••" else summary.holdingPnl?.let(::signedAmount) ?: "--")
        text(R.id.overviewReturn, if (hidden) "••••" else summary.holdingReturn?.let(::percent) ?: "--")
        tone(R.id.overviewPnl, summary.holdingPnl)
        tone(R.id.overviewReturn, summary.holdingReturn)
        text(R.id.positionCount, "共 ${state.positions.count { it.shares > 0 }} 只")
        text(R.id.exposureStock, "● 股票 ${if (hidden) "••" else summary.positionPct?.let { decimal(it, 1) + "%" } ?: "--"}")
        text(R.id.exposureCash, "● 现金 ${if (hidden) "••" else summary.positionPct?.let { decimal(100 - it, 1) + "%" } ?: "--"}")
        graph(R.id.exposureRing)?.gauge(if (hidden) null else summary.positionPct, if (hidden) "••" else summary.positionPct?.let { decimal(it, 1) + "%" } ?: "--", "当前仓位", true)
        root.findViewById<View>(R.id.privacyToggle)?.contentDescription = if (hidden) "显示金额" else "隐藏金额"
        val index = state.snapshot?.marketIndices?.firstOrNull { it.code == "000001.SH" }
        text(R.id.accountMarketChange, "今日大盘 ${index?.changeRatio?.let(::percent) ?: "--"} ›")
        graph(R.id.accountMarketGraph)?.line(charts.indices["000001.SH"]?.values.orEmpty(), positive = (index?.changeRatio ?: 0.0) >= 0.0)
        graph(R.id.equityGraph)?.line(if (hidden) emptyList() else charts.accountReturns, charts.returnDates, true, if (hidden) emptyList() else charts.benchmarkReturns, if (hidden) "收益已隐藏" else "暂无可核验的收益序列")
        text(R.id.equityNote, if (charts.accountReturns.isEmpty()) "收益需现金流与费用校正，暂不以持仓市值替代" else "收益率（%）· 以数据来源为准")
        renderPositions(state, hidden)
    }

    /** 持仓行按名称、数量、现价成本、盈亏及比例分列；窄屏也不拼成长段文字。 */
    private fun renderPositions(state: MainUiState, hidden: Boolean) {
        val container = root.findViewById<LinearLayout>(R.id.positionRows) ?: return
        container.removeAllViews()
        val quotes = state.snapshot?.quotes.orEmpty().associateBy { SettingsRepository.normalizeCode(it.code) }
        state.positions.filter { it.shares > 0 }.forEach { position ->
            val quote = quotes[SettingsRepository.normalizeCode(position.code)]
            val price = quote?.latest.validPrice()
            val pnl = price?.let { (it - position.cost) * position.shares }
            val percent = price?.takeIf { position.cost > 0 }?.let { (it / position.cost - 1) * 100 }
            val row = horizontal().apply { setPadding(0, dp(9), 0, dp(9)) }
            row.addView(cell(position.name.ifBlank { quote?.name.orEmpty() }, position.code.substringBefore('.'), 1.1f))
            row.addView(cell(if (hidden) "••" else position.shares.toString(), if (hidden) "••" else position.availableShares?.toString() ?: "未知", 0.85f, true))
            row.addView(cell(price?.let(::decimal) ?: "--", if (hidden) "••" else decimal(position.cost), 1f, true))
            row.addView(cell(if (hidden) "••" else pnl?.let(::signedAmount) ?: "--", "", 1.15f, true, colorFor(pnl)))
            row.addView(cell(if (hidden) "••" else percent?.let(::percent) ?: "--", "", 0.9f, true, colorFor(percent)))
            row.setOnClickListener { action("stock:${position.code}") }
            row.contentDescription = "${position.name}，查看持仓详情"
            container.addView(row)
            separator(container)
        }
        if (container.childCount == 0) empty(container, "暂无持仓 · 点击核对导入添加")
    }

    /** 领涨列表和水平资金条沿用参考图布局，真实数据不足时显示空态。 */
    private fun renderHotspots(state: MainUiState) {
        val container = root.findViewById<LinearLayout>(R.id.leaderRows) ?: return
        container.removeAllViews()
        val sectors = state.sectorFundFlow?.rows.orEmpty().sortedByDescending { it.mainNet }
        val quotes = state.snapshot?.quotes.orEmpty().associateBy { SettingsRepository.normalizeCode(it.code) }
        val leaders = sectors.filter { it.leadStockName.isNotBlank() }.distinctBy { it.leadStockName }.take(5)
        leaders.forEachIndexed { index, sector ->
            val code = SettingsRepository.normalizeCode(sector.leadStockCode)
            val quote = quotes[code]
            val row = horizontal().apply { setPadding(0, dp(7), 0, dp(7)) }
            row.addView(badge((index + 1).toString(), index))
            row.addView(cell(sector.leadStockName, sector.leadStockCode, 1.5f))
            row.addView(label(sector.name.take(6), 10f, DesignR.color.gh_red).apply { background = rounded(DesignR.color.gh_blush); setPadding(dp(4), dp(3), dp(4), dp(3)) })
            row.addView(cell(quote?.latest.validPrice()?.let(::decimal) ?: "--", "", 0.9f, true, colorFor(quote?.changeRatio)))
            row.addView(label(quote?.changeRatio?.let(::percent) ?: "待报价", 12f, DesignR.color.guheng_on_brand).apply {
                background = rounded(colorFor(quote?.changeRatio))
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            })
            if (code.matches(Regex("\\d{6}\\.(SH|SZ|BJ)"))) row.setOnClickListener { action("stock:$code") }
            container.addView(row)
            separator(container)
        }
        if (leaders.isEmpty()) empty(container, "暂无板块领涨股数据")
        root.findViewById<LinearLayout>(R.id.strengthRows)?.let { bars ->
            bars.removeAllViews()
            val top = sectors.take(5)
            val max = top.maxOfOrNull { abs(it.mainNet) }?.coerceAtLeast(1.0) ?: 1.0
            top.forEach { sector -> barRow(bars, sector.name, abs(sector.mainNet) / max, compact(sector.mainNet), sector.mainNet >= 0) }
            if (top.isEmpty()) empty(bars, "暂无板块主力资金数据")
        }
    }

    /** AI 与本地计划保持来源分离，置信信息不改名为胜率或未来上涨概率。 */
    private fun renderStrategy(state: MainUiState, hidden: Boolean) {
        if (root.findViewById<View>(R.id.strategyRing) == null) return
        val summary = ReferenceAccountMapper.map(state)
        val ai = state.aiStrategy
        graph(R.id.strategyCandles)?.candles(state.dailyBars)
        text(R.id.strategyChartName, state.selectedCode ?: "日 K 行情")
        text(R.id.strategyName, if (ai == null) "本地风险与交易计划" else "AI 与本地交易策略")
        text(R.id.strategyPositions, "${state.positions.count { it.shares > 0 }} 只")
        text(R.id.strategyTodayLabel, summary.todayLabel)
        text(R.id.strategyToday, if (hidden) "••" else summary.today?.let(::compact) ?: "--")
        text(R.id.strategyTotal, if (hidden) "••" else summary.holdingPnl?.let(::compact) ?: "--")
        graph(R.id.strategyRing)?.gauge(ai?.confidence?.toDouble(), ai?.confidence?.toString() ?: "--", "AI 置信信息", true)
        text(R.id.aiComment, if (state.aiLoading) "AI 正在分析…" else ai?.let { "AI 点评：${it.stocks.firstOrNull()?.reason ?: it.marketAction}。风险等级 ${it.riskLevel}；本地计划独立展示。" } ?: "AI 点评：暂无结构化分析。可生成分析，或先查看本地交易计划。")
        root.findViewById<LinearLayout>(R.id.strategyScoreRows)?.let { rows ->
            rows.removeAllViews()
            listOf("胜率能力", "盈亏比", "风险控制", "稳定性").forEach { barRow(rows, it, 0.0, "待评估", true) }
        }
        root.findViewById<LinearLayout>(R.id.planRows)?.let { rows ->
            rows.removeAllViews()
            state.positionPlans.take(8).forEach { plan ->
                val line = horizontal().apply { setPadding(0, dp(9), 0, dp(9)) }
                line.addView(badge(plan.action.take(1), 0))
                line.addView(cell("${plan.name}  ${plan.code.substringBefore('.')}", plan.reason, 1f))
                line.addView(label("本地计划", 10f, DesignR.color.gh_red).apply { background = rounded(DesignR.color.gh_pink); setPadding(dp(5), dp(4), dp(5), dp(4)) })
                line.setOnClickListener { action("stock:${plan.code}") }
                rows.addView(line)
                separator(rows)
            }
            if (rows.childCount == 0) empty(rows, "暂无交易计划 · 配置持仓并刷新行情")
        }
    }

    /** 自选采用参考图的紧凑行情行，只过滤当前监控集合。 */
    private fun renderWatchlist(state: MainUiState, query: String) {
        val rows = root.findViewById<LinearLayout>(R.id.watchlistRows) ?: return
        rows.removeAllViews()
        val quotes = state.snapshot?.quotes.orEmpty().associateBy { SettingsRepository.normalizeCode(it.code) }
        val positions = state.positions.associateBy { SettingsRepository.normalizeCode(it.code) }
        state.monitoredCodes.map(SettingsRepository::normalizeCode).distinct().forEach { code ->
            val quote = quotes[code]
            val name = quote?.name?.takeIf(String::isNotBlank) ?: positions[code]?.name.orEmpty()
            if (query.isNotBlank() && !code.contains(query, true) && !name.contains(query, true)) return@forEach
            val row = horizontal().apply { setPadding(0, dp(11), 0, dp(11)) }
            row.addView(cell(name.ifBlank { code }, code, 1.4f))
            row.addView(cell(quote?.latest.validPrice()?.let(::decimal) ?: "--", quote?.time?.takeLast(8).orEmpty(), 1f, true, colorFor(quote?.changeRatio)))
            row.addView(cell(quote?.changeRatio?.let(::percent) ?: "--", "", 1f, true, colorFor(quote?.changeRatio)))
            row.setOnClickListener { action("stock:$code") }
            rows.addView(row)
            separator(rows)
        }
        if (rows.childCount == 0) empty(rows, if (query.isBlank()) "暂无监控证券 · 请先添加自选" else "当前列表没有匹配的证券")
    }

    /** 将配置和原驾驶舱收纳为轻量列表入口，保持旧功能可达。 */
    private fun renderProfile(state: MainUiState) {
        text(R.id.monitorStatus, if (state.monitorRunning) "行情监控运行中" else "行情监控已停止")
        val rows = root.findViewById<LinearLayout>(R.id.profileActions) ?: return
        if (rows.childCount > 0) return
        listOf("交易计划与复盘" to "tools", "策略与 AI 分析" to "strategy", "同花顺核对导入" to "sync", "消息中心" to "messages", "持仓 / 数据源 / AI / 备份设置" to "settings", "启动监控" to "start_monitor", "停止监控" to "stop_monitor").forEach { (name, route) ->
            val row = horizontal().apply { minimumHeight = dp(48) }
            row.addView(cell(name, "", 1f))
            row.addView(label("›", 21f, DesignR.color.gh_secondary))
            row.setOnClickListener { action(route) }
            rows.addView(row)
            separator(rows)
        }
    }

    /** 创建名称、条形与数值三列的指标行，未知指标使用灰色轨道。 */
    private fun barRow(container: LinearLayout, name: String, ratio: Double, value: String, positive: Boolean) {
        val row = horizontal().apply { minimumHeight = dp(28) }
        row.addView(label(name, 11f, DesignR.color.gh_secondary).apply { layoutParams = LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT) })
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(DesignR.color.gh_line)
            layoutParams = LinearLayout.LayoutParams(0, dp(7), 1f)
        }
        val portion = ratio.coerceIn(0.0, 1.0).toFloat()
        if (portion > 0) track.addView(View(context).apply { background = rounded(if (positive) DesignR.color.gh_red else DesignR.color.gh_green) }, LinearLayout.LayoutParams(0, -1, portion))
        if (portion < 1) track.addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - portion))
        row.addView(track)
        row.addView(label(value, 11f, if (positive) DesignR.color.gh_red else DesignR.color.gh_green).apply { gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(dp(55), -2) })
        container.addView(row)
    }

    /** 生成双行表格单元格，所有数值列右对齐。 */
    private fun cell(first: String, second: String, weight: Float, right: Boolean = false, tint: Int = DesignR.color.gh_text): LinearLayout = column().apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, weight)
        if (right) gravity = Gravity.END
        addView(label(first, 12f, tint, true).apply { if (right) gravity = Gravity.END })
        if (second.isNotBlank()) addView(label(second, 10f, DesignR.color.gh_secondary).apply { if (right) gravity = Gravity.END })
    }

    /** 绘制排行榜序号和计划标记，与参考图使用同一圆角方块。 */
    private fun badge(value: String, index: Int): TextView = label(value, 13f, DesignR.color.guheng_on_brand).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (index < 2) color(DesignR.color.gh_red) else if (index == 2) 0xFFF3A13C.toInt() else 0xFFAAAAAA.toInt()) }
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(24)).apply { marginEnd = dp(8) }
    }

    /** 创建统一字号与主题色的文字控件。 */
    private fun label(value: String, size: Float, tint: Int = DesignR.color.gh_text, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(tint))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    /** 创建横向信息行。 */
    private fun horizontal() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }

    /** 创建纵向信息单元。 */
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** 在空列表中放置轻量提示，保持卡片层级和最小可读高度。 */
    private fun empty(container: LinearLayout, value: String) { container.addView(label(value, 12f, DesignR.color.gh_secondary).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(25), dp(8), dp(25)); layoutParams = LinearLayout.LayoutParams(-1, -2) }) }

    /** 列表分隔线采用低对比灰色，避免重复描边卡片造成视觉拥挤。 */
    private fun separator(container: LinearLayout) { container.addView(View(context).apply { setBackgroundColor(color(DesignR.color.gh_line)) }, LinearLayout.LayoutParams(-1, dp(1))) }

    /** 为标签和数据条创建主题感知的圆角背景。 */
    private fun rounded(tint: Int) = GradientDrawable().apply { setColor(color(tint)); cornerRadius = dp(4).toFloat() }

    /** 仅更新当前页面存在的文字节点。 */
    private fun text(id: Int, value: String) { root.findViewById<TextView>(id)?.text = value }

    /** 获取当前页面的图表节点，不要求其他页面同时加载。 */
    private fun graph(id: Int): MarketGraphicView? = root.findViewById(id)

    /** 数值方向使用红涨绿跌，缺失值使用正文色。 */
    private fun tone(id: Int, value: Double?) { root.findViewById<TextView>(id)?.setTextColor(color(colorFor(value))) }

    /** 将正负值映射到语义色资源。 */
    private fun colorFor(value: Double?) = when { value == null || value == 0.0 -> DesignR.color.gh_text; value > 0 -> DesignR.color.gh_red; else -> DesignR.color.gh_green }

    /** 读取当前主题颜色。 */
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    /** 将参考设计间距转换为像素。 */
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** 过滤停牌占位、非有限数和缺失价格。 */
    private fun Double?.validPrice() = this?.takeIf { it.isFinite() && it > 0 }

    /** 价格和指标采用固定精度。 */
    private fun decimal(value: Double, digits: Int = 2) = String.format(Locale.CHINA, "%.${digits}f", value)

    /** 账户金额使用千位分隔，贴合参考图的资产排版。 */
    private fun amount(value: Double) = String.format(Locale.CHINA, "%,.2f", value)

    /** 盈亏金额保留方向和千位分隔。 */
    private fun signedAmount(value: Double) = String.format(Locale.CHINA, "%+,.2f", value)

    /** 涨跌点数保留显式正负号。 */
    private fun signed(value: Double) = String.format(Locale.CHINA, "%+.2f", value)

    /** 已为百分数的字段直接附加百分号，不再次放大。 */
    private fun percent(value: Double) = signed(value) + "%"

    /** 紧凑卡片中的大金额按万、亿缩写。 */
    private fun compact(value: Double) = when { abs(value) >= 100_000_000 -> signed(value / 100_000_000) + "亿"; abs(value) >= 10_000 -> signed(value / 10_000) + "万"; else -> signed(value) }

    /** 展示上海时区的更新时间，缺时间时保持未知。 */
    private fun time(value: Long) = if (value <= 0) "时间未知" else Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

/** 参考账户布局需要的展示值，不新增账户计算或持久化口径。 */
data class ReferenceAccountSummary(val total: Double?, val today: Double?, val todayLabel: String, val todayStatus: String, val marketValue: Double?, val holdingPnl: Double?, val holdingReturn: Double?, val positionPct: Double?, val source: String)

/** 在复用原账户映射的同时，对新资产大号展示补充完整性和导入优先级。 */
object ReferenceAccountMapper {
    /** 优先展示用户确认的有效导入值；本地资产缺价时不展示部分合计冒充总资产。 */
    fun map(state: MainUiState, now: Long = System.currentTimeMillis()): ReferenceAccountSummary {
        val local = DashboardSummaryMapper.account(state.snapshot, state.positions, state.cashBalance, now)
        val positions = state.positions.filter { it.shares > 0 }
        val quotes = state.snapshot?.quotes.orEmpty().associateBy { it.code }
        val complete = positions.all { position -> quotes[position.code]?.let { (it.latest ?: 0.0) > 0 || (it.previousClose ?: 0.0) > 0 } == true }
        val imported = state.importedAccountMetrics.filter { entity -> ThsAccountMetric.entries.any { it.name == entity.metric && it.valid(entity.value) } }
        val totalImport = imported.find { it.metric == ThsAccountMetric.TOTAL_ASSETS.name }
        val todayImport = imported.find { it.metric == ThsAccountMetric.TODAY_PNL.name }
        val positionImport = imported.find { it.metric == ThsAccountMetric.POSITION_PCT.name }
        val todayTitle = ImportedAccountPresenter.text(ThsAccountMetric.TODAY_PNL, imported, now)?.substringBefore('\n') ?: "当日盈亏"
        val cost = positions.sumOf { it.shares * it.cost }
        val holding = local.cumulativePnl.takeIf { complete }
        val timestamp = totalImport?.observedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }
        val source = totalImport?.let { "${if (it.source == "THS_MANUAL") "手工核对" else "同花顺导入"} · $timestamp · 其他字段按各自来源" }
            ?: if (!complete) "本地估值不完整 · 部分持仓缺少行情" else "本地持仓与配置现金估值 · 非券商实时余额"
        return ReferenceAccountSummary(
            totalImport?.value ?: local.totalAssets.takeIf { complete }, todayImport?.value ?: local.todayPnl,
            todayTitle, if (local.todayPnlState == "PREOPEN") "待开盘" else "--", local.marketValue.takeIf { complete },
            holding, holding?.takeIf { cost > 0 }?.let { it / cost * 100 },
            positionImport?.value ?: local.positionPct.takeIf { complete && state.cashBalance != null }, source
        )
    }
}
