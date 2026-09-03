package com.locogo.astockguard

/*
 * 文件职责：执行交易时段后台轮询、策略评价和通知去重；任何可操作提醒都必须通过交易时段、实时数据与状态变化三重门禁。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.locogo.astockguard.chart.MinuteCandleAggregator
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.news.NewsRepository
import com.locogo.astockguard.data.level2.Level2Repository
import com.locogo.astockguard.domain.strategy.ChartSignalAction
import com.locogo.astockguard.domain.strategy.IntradaySignalEngine
import com.locogo.astockguard.domain.review.AlertHistoryRepository
import com.locogo.astockguard.domain.trading.TTradePlanner
import com.locogo.astockguard.domain.plan.AuctionPlanEngine
import com.locogo.astockguard.domain.plan.PortfolioExposureEngine
import kotlinx.coroutines.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class MarketMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var settings: SettingsRepository
    private lateinit var marketRepository: MarketRepository
    private lateinit var newsRepository: NewsRepository
    private lateinit var fundFlowRepository: FundFlowRepository
    private lateinit var level2Repository: Level2Repository
    private lateinit var alertHistoryRepository: AlertHistoryRepository
    private var lastRisk = ""
    private var lastNewsRisk = "E0"
    private var lastNewsRefreshAt = 0L
    private var lastTScanAt = 0L
    private var lastIntradayScanAt = 0L
    private var lastAuctionScanAt = 0L
    private val lastTStatus = mutableMapOf<String, String>()
    private val lastIntradayAlert = mutableMapOf<String, String>()
    private val lastAuctionStatus = mutableMapOf<String, String>()
    private var lastExposureStatus = ""
    private lateinit var signalLifecycle: com.locogo.astockguard.domain.signal.SignalLifecycleManager

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        settings = appContainer.settings
        marketRepository = appContainer.marketRepository
        newsRepository = appContainer.newsRepository
        fundFlowRepository = appContainer.fundFlowRepository
        level2Repository = appContainer.level2Repository
        alertHistoryRepository = appContainer.alertHistoryRepository
        signalLifecycle = appContainer.signalLifecycle
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MonitorBus.updateRunning(false)
            stopSelf()
        } else startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, NotificationHelper.serviceNotification(this, "行情源初始化..."))
        MonitorBus.updateRunning(true)
        loopJob = scope.launch {
            while (isActive) {
                try {
                    val s = marketRepository.refresh()
                    MonitorBus.update(s)
                    emitAlerts(s)
                    emitExposureAlert(s)
                    refreshNewsIfDue()
                    scanAuctionPlansIfDue(s)
                    scanTPlansIfDue(s)
                    scanIntradaySignalsIfDue(s)
                    val source = if (s.dataHealth.isStale) "缓存" else "实时"
                    val text = "$source ${s.assessment.eventRisk}/${s.assessment.marketPhase} 仓位${"%.1f".format(s.positionRatio)}% 上限${"%.0f".format(s.assessment.maxPositionRatio * 100)}%"
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, NotificationHelper.serviceNotification(this@MarketMonitorService, text))
                } catch (t: Throwable) {
                    Log.e("MarketMonitor", "refresh failed: ${t.message}", t)
                }
                delay(settings.refreshSeconds * 1000L)
            }
        }
    }

    private suspend fun refreshNewsIfDue() {
        if (!settings.newsEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastNewsRefreshAt < settings.newsRefreshMinutes * 60_000L) return
        lastNewsRefreshAt = now
        val news = runCatching { newsRepository.refresh() }.getOrElse { newsRepository.cached() }
        if (news.level != lastNewsRisk && news.level == "E2") {
            val evidence = news.evidence.take(3).joinToString("；") { it.title }
            NotificationHelper.alert(
                this,
                2101,
                "新闻黑天鹅 E2 · score ${news.score}",
                evidence.ifBlank { "新闻风险覆盖层升级，请人工核实证据后再调整仓位。" }
            )
        }
        lastNewsRisk = news.level
    }

    private suspend fun scanTPlansIfDue(snapshot: MonitorSnapshot) {
        if (snapshot.dataHealth.isStale) return
        val now = System.currentTimeMillis()
        if (now - lastTScanAt < T_SCAN_INTERVAL_MS || !isAshareTradingTime(now)) return
        lastTScanAt = now

        val quoteMap = snapshot.quotes.associateBy { it.code }
        settings.positions().asSequence()
            .filter { it.shares >= 100 }
            .take(MAX_T_SCAN_POSITIONS)
            .forEach { position ->
                val quote = quoteMap[position.code] ?: return@forEach
                val bars = runCatching { marketRepository.loadMinuteBars(position.code) }.getOrDefault(emptyList())
                val fundFlow = runCatching { fundFlowRepository.stock(position.code) }.getOrNull()
                // 真实盘口不可用时仍可生成基础计划，但引擎会明确排除 MOCK、缓存和超时快照。
                val level2 = runCatching { level2Repository.snapshot(position.code, quote.latest) }.getOrNull()
                // 网络请求完成后重新取时，避免接收时间比本轮开始时间晚几毫秒而被误判为未来数据。
                val evaluationNow = System.currentTimeMillis()
                val level2History = if (level2?.simulated == false && level2.stale.not()) {
                    runCatching { level2Repository.recentSnapshots(position.code, evaluationNow) }.getOrDefault(emptyList())
                } else emptyList()
                val plan = TTradePlanner.plan(
                    quote = quote,
                    position = position,
                    minuteBars = bars,
                    fundFlow = fundFlow,
                    marketPhase = snapshot.assessment.marketPhase,
                    dataStale = snapshot.dataHealth.isStale,
                    now = evaluationNow,
                    level2 = level2,
                    level2History = level2History
                )
                val previous = lastTStatus.put(position.code, plan.status)
                if (previous == plan.status) return@forEach

                if (plan.actionable) {
                    val evidenceSource = buildString {
                        append(snapshot.dataHealth.source)
                        append("+FUND_").append(plan.fundFlowStatus)
                        append("+L2_").append(level2?.source ?: "NONE")
                        append("+").append(plan.orderBookPersistence)
                    }
                    // 只有成功写入Room且通过跨进程唯一键去重的提醒，才进入通知栏和真实胜率统计。
                    val recorded = alertHistoryRepository.recordTPlanAlert(
                        name = position.name,
                        plan = plan,
                        dataSource = evidenceSource,
                        now = evaluationNow
                    )
                    if (!recorded) return@forEach
                }

                when (plan.status) {
                    "BUY_ZONE" -> NotificationHelper.alert(
                        this,
                        tNotificationId(position.code, 1),
                        "${position.name} · T买入观察区",
                        "${"%.2f".format(plan.buyZoneLow)}~${"%.2f".format(plan.buyZoneHigh)}，建议T仓${plan.suggestedQuantity}股；目标卖区${"%.2f".format(plan.sellZoneLow)}~${"%.2f".format(plan.sellZoneHigh)}，失效位${"%.2f".format(plan.invalidPrice)}。等待承接确认，不自动下单。"
                    )
                    "SELL_ZONE" -> NotificationHelper.alert(
                        this,
                        tNotificationId(position.code, 2),
                        "${position.name} · T卖出观察区",
                        "现价${quote.latest?.let { "%.2f".format(it) } ?: "-"}进入/超过计划卖区${"%.2f".format(plan.sellZoneLow)}~${"%.2f".format(plan.sellZoneHigh)}；评估卖出T仓${plan.suggestedQuantity}股，保留底仓纪律。"
                    )
                    "INVALIDATED" -> NotificationHelper.alert(
                        this,
                        tNotificationId(position.code, 3),
                        "${position.name} · T计划失效",
                        "价格跌破计划失效位${"%.2f".format(plan.invalidPrice)}，停止机械补仓，重新评估趋势和风险。"
                    )
                }
            }
    }

    /** 只在实时行情下提示组合级降仓顺序；同一状态不重复轰炸通知。 */
    private fun emitExposureAlert(snapshot: MonitorSnapshot) {
        val plan = PortfolioExposureEngine.evaluate(
            settings.positions(), snapshot.quotes, snapshot.assessment, snapshot.positionRatio, snapshot.dataHealth.isStale
        )
        // 数量可能随价格小幅变化，去重键只跟状态、仓位上限和减仓顺序绑定，避免高频重复提醒。
        val stateKey = "${plan.status}:${plan.targetPositionPct.toInt()}:${plan.reductions.joinToString { it.code }}"
        if (!plan.actionable) {
            lastExposureStatus = stateKey
            return
        }
        if (stateKey == lastExposureStatus) return
        lastExposureStatus = stateKey
        val actions = plan.reductions.take(4).joinToString("；") { "${it.name}减${it.quantity}股" }
        NotificationHelper.alert(
            this,
            EXPOSURE_NOTIFICATION_ID,
            "组合回撤防守 · 建议降低仓位",
            "当前${"%.1f".format(plan.currentPositionPct)}%，目标不高于${"%.1f".format(plan.targetPositionPct)}%。$actions。${plan.reason}"
        )
    }

    /**
     * 9:15—9:30 使用 iFinD 真实快照更新集合竞价预案。
     * 免费源、缓存或无权限状态不会触发竞价提醒，且任何强竞价都要求开盘后二次确认。
     */
    private suspend fun scanAuctionPlansIfDue(snapshot: MonitorSnapshot) {
        if (snapshot.dataHealth.isStale || settings.marketDataSource != SettingsRepository.MARKET_SOURCE_IFIND) return
        val now = System.currentTimeMillis()
        if (!isAuctionTime(now) || now - lastAuctionScanAt < AUCTION_SCAN_INTERVAL_MS) return
        lastAuctionScanAt = now
        val quoteMap = snapshot.quotes.associateBy { it.code }
        settings.positions().take(MAX_AUCTION_SCAN_POSITIONS).forEach { position ->
            runCatching {
                val series = marketRepository.loadAuctionSeries(position.code)
                if (!series.fresh || series.ticks.isEmpty()) return@runCatching
                val daily = marketRepository.loadDailyBars(position.code, 12)
                val plan = AuctionPlanEngine.evaluate(
                    position.code, position.role, quoteMap[position.code]?.previousClose, daily, series
                )
                if (plan.status == "NO_DATA") return@runCatching
                val previous = lastAuctionStatus.put(position.code, plan.status)
                if (previous == plan.status) return@runCatching
                NotificationHelper.alert(
                    this,
                    auctionNotificationId(position.code),
                    "${position.name} · ${plan.status}",
                    "竞价评分${plan.score}，${plan.reason} 数据源${plan.source}。仅更新开盘预案，不自动下单。"
                )
            }.onFailure { error ->
                Log.w("MarketMonitor", "集合竞价扫描失败 ${position.code}: ${error.message}")
            }
        }
    }

    /**
     * 扫描监控池中的实时五分钟买卖点。
     * 只有行情接口本次成功且处于交易时段才允许通知；缓存数据仅用于展示和回看。
     */
    private suspend fun scanIntradaySignalsIfDue(snapshot: MonitorSnapshot) {
        if (snapshot.dataHealth.isStale) return
        val now = System.currentTimeMillis()
        if (now - lastIntradayScanAt < INTRADAY_SCAN_INTERVAL_MS || !isAshareTradingTime(now)) return
        lastIntradayScanAt = now

        val quoteMap = snapshot.quotes.associateBy { it.code }
        val codes = (settings.positions().map { it.code } + settings.allCodes())
            .distinct()
            .take(MAX_INTRADAY_SCAN_CODES)
        codes.forEach { code ->
            runCatching {
                val series = marketRepository.loadMinuteSeries(code)
                if (series.fromCache || series.isHistorical || series.bars.isEmpty()) return@runCatching
                val candles = MinuteCandleAggregator.aggregate(series.bars)
                if (candles.size < MIN_INTRADAY_CANDLES) return@runCatching
                // 每轮先评价历史待定提醒；不足六根后续K线时仍保持待定，不制造提前结论。
                alertHistoryRepository.evaluatePending(code, series.date, candles, now)

                val flow = fundFlowRepository.stock(code)
                val freshFlow = flow.minute.takeIf {
                    !flow.minuteStale && isMinuteFlowCurrent(it.lastOrNull()?.time, now)
                }.orEmpty()
                val signal = IntradaySignalEngine.evaluate(candles, dataIsFresh = true, fundFlow = freshFlow)
                    .asSequence()
                    .filterNot { it.recommended }
                    .lastOrNull() ?: return@runCatching
                // 服务刚启动时不补发早盘旧信号，只提醒最近完成的确认K线。
                if (!isSignalRecent(signal.time, now)) return@runCatching
                val alertKey = "${signal.time}:${signal.action}"
                if (lastIntradayAlert[code] == alertKey) return@runCatching

                val quote = quoteMap[code]
                // Room唯一键负责跨进程去重；只有成功落库的实时提醒才真正发通知并进入胜率统计。
                val recorded = alertHistoryRepository.recordLiveAlert(
                    code = code,
                    name = quote?.name.orEmpty(),
                    date = series.date,
                    signal = signal,
                    dataSource = if (freshFlow.isEmpty()) series.source else "${series.source}+FUND_FLOW"
                )
                if (!recorded) return@runCatching
                lastIntradayAlert[code] = alertKey
                val actionText = if (signal.action == ChartSignalAction.BUY) "买入观察" else "卖出观察"
                val flowText = if (freshFlow.isEmpty()) "量能确认" else "主力资金与量能确认"
                NotificationHelper.alert(
                    this,
                    intradayNotificationId(code, signal.action),
                    "${quote?.name?.ifBlank { code } ?: code} · 分时$actionText",
                    "${signal.time} 参考价${"%.2f".format(signal.price)}，评分${signal.score}；$flowText。${signal.reason}。仅作提醒，不自动下单。"
                )
            }.onFailure { error ->
                Log.w("MarketMonitor", "分时信号扫描失败 $code: ${error.message}")
            }
        }
    }

    /** 判断分钟资金流是否足够接近当前时间，防止旧缓存参与实时提醒。 */
    private fun isMinuteFlowCurrent(value: String?, now: Long): Boolean =
        value != null && minutesFromNow(value, now)?.let { it in 0..MAX_FLOW_AGE_MINUTES } == true

    /** 信号时间允许少量接口和轮询延迟，但不能跨越午休后补发。 */
    private fun isSignalRecent(value: String, now: Long): Boolean =
        minutesFromNow(value, now)?.let { it in 0..MAX_SIGNAL_AGE_MINUTES } == true

    private fun minutesFromNow(value: String, now: Long): Int? {
        val match = TIME_REGEX.find(value) ?: return null
        val signalMinutes = (match.groupValues[1].toIntOrNull() ?: return null) * 60 +
            (match.groupValues[2].toIntOrNull() ?: return null)
        val current = Instant.ofEpochMilli(now).atZone(CHINA_ZONE).toLocalTime()
        return current.hour * 60 + current.minute - signalMinutes
    }

    private suspend fun emitAlerts(s: MonitorSnapshot) {
        if (s.dataHealth.isStale) return
        val risk = s.assessment.eventRisk
        if (risk != lastRisk && risk == "E2") NotificationHelper.alert(this, 2001, "本地行情 E2 风险：先降β", s.assessment.advice)
        lastRisk = risk
        signalLifecycle.evaluate(s).forEachIndexed { index, transition ->
            if (!transition.important) return@forEachIndexed
            if (!signalLifecycle.canNotify(transition.code, s.updatedAt)) return@forEachIndexed
            NotificationHelper.alert(this, 3000 + index, "${transition.code} ${transition.from.name} → ${transition.to.name}", transition.reason)
            signalLifecycle.markNotified(transition.code, s.updatedAt)
        }
    }

    private fun isAshareTradingTime(epochMs: Long): Boolean {
        val dt = Instant.ofEpochMilli(epochMs).atZone(CHINA_ZONE)
        if (dt.dayOfWeek == DayOfWeek.SATURDAY || dt.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = dt.toLocalTime()
        val morning = !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(11, 30))
        val afternoon = !t.isBefore(LocalTime.of(13, 0)) && !t.isAfter(LocalTime.of(15, 0))
        return morning || afternoon
    }

    private fun tNotificationId(code: String, type: Int): Int {
        val positiveHash = code.hashCode() and Int.MAX_VALUE
        return 4000 + (positiveHash % 700) * 4 + type
    }

    private fun isAuctionTime(epochMs: Long): Boolean {
        val dt = Instant.ofEpochMilli(epochMs).atZone(CHINA_ZONE)
        if (dt.dayOfWeek == DayOfWeek.SATURDAY || dt.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = dt.toLocalTime()
        return !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(9, 30))
    }

    private fun auctionNotificationId(code: String): Int =
        11_000 + ((code.hashCode() and Int.MAX_VALUE) % 900)

    private fun intradayNotificationId(code: String, action: ChartSignalAction): Int {
        val positiveHash = code.hashCode() and Int.MAX_VALUE
        return 7000 + (positiveHash % 900) * 2 + if (action == ChartSignalAction.BUY) 0 else 1
    }

    override fun onDestroy() {
        MonitorBus.updateRunning(false)
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }

    companion object {
        const val ACTION_START = "com.locogo.astockguard.START"
        const val ACTION_STOP = "com.locogo.astockguard.STOP"
        const val NOTIFICATION_ID = 1001
        private const val T_SCAN_INTERVAL_MS = 30_000L
        private const val INTRADAY_SCAN_INTERVAL_MS = 60_000L
        private const val AUCTION_SCAN_INTERVAL_MS = 15_000L
        private const val MAX_T_SCAN_POSITIONS = 6
        private const val MAX_AUCTION_SCAN_POSITIONS = 6
        private const val MAX_INTRADAY_SCAN_CODES = 6
        private const val MIN_INTRADAY_CANDLES = 8
        private const val MAX_FLOW_AGE_MINUTES = 10
        private const val MAX_SIGNAL_AGE_MINUTES = 12
        private const val EXPOSURE_NOTIFICATION_ID = 10_801
        private val TIME_REGEX = Regex("(\\d{2}):(\\d{2})")
        private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
