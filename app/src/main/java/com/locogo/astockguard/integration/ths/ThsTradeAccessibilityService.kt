package com.locogo.astockguard.integration.ths

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.data.local.TradeRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * 同花顺只读辅助同步服务。
 *
 * 服务不会执行点击或下单，也不会保存整页文本；仅在用户手动打开持仓、成交或交割单页面时，
 * 提取当前可见的结构化字段。节点树必须在主线程读取，数据库操作再切到 IO 线程。
 */
class ThsTradeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scheduledScan: Job? = null
    private var pollingJob: Job? = null
    private var manualScanJob: Job? = null
    private var lastScanAt = 0L
    private var lastNameResolveAt = 0L
    private var lastAccountPersistAt = 0L
    private val packageMatchCache = mutableMapOf<String, Boolean>()
    private val resolvedNameCodes = mutableMapOf<String, String>()
    private var lastNotificationStatus = ""
    private var lastSuccessfulStatus = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 授权开关不等于系统已成功绑定；记录此状态方便在同步页直接判断服务是否真正运行。
        lastSuccessfulStatus = appContainer.settings.thsLastSuccessMessage
        val connectedStatus = if (lastSuccessfulStatus.isBlank()) {
            "辅助服务已连接，等待打开同花顺持仓或成交页"
        } else {
            "辅助服务已连接；上次结果：$lastSuccessfulStatus"
        }
        publishStatus(connectedStatus)
        android.util.Log.i("ThsTradeSync", "Accessibility service connected")
        startForegroundWindowPolling()
        observeManualScanRequests()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        val pkg = currentEvent.packageName?.toString().orEmpty()
        if (!isTonghuashunPackage(pkg)) return
        if (currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            currentEvent.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val now = System.currentTimeMillis()
        val waitMillis = (MIN_SCAN_INTERVAL_MS - (now - lastScanAt)).coerceAtLeast(0L)
        if (waitMillis == 0L) {
            // 行情数字持续刷新时不能反复取消任务，否则扫描会被永久推迟。
            scheduledScan?.cancel()
            scheduledScan = null
            lastScanAt = now
            scope.launch { syncVisibleData(pkg, currentEvent.source) }
        } else if (scheduledScan?.isActive != true) {
            scheduledScan = scope.launch {
                delay(waitMillis)
                lastScanAt = System.currentTimeMillis()
                syncVisibleData(pkg, null)
                scheduledScan = null
            }
        }
    }

    /** 扫描当前窗口，并分别同步持仓与成交；原始节点文本只在内存中短暂存在。 */
    private suspend fun syncVisibleData(
        packageName: String,
        eventSource: AccessibilityNodeInfo?,
        rootOverride: AccessibilityNodeInfo? = null,
        userInitiated: Boolean = false
    ) {
        val root = rootOverride ?: findReadableRoot()
        if (root == null && eventSource == null) {
            publishStatus("检测到同花顺，但暂时无法读取当前窗口，请停留在持仓页后重试")
            return
        }
        // 延迟期间用户可能已经切换应用，避免把其他窗口误当作同花顺页面解析。
        val activePackage = root?.packageName?.toString().orEmpty()
        if (activePackage.isNotBlank() && !isTonghuashunPackage(activePackage)) return
        val texts = ArrayList<String>(128)
        root?.let { collectTexts(it, texts, 0) }
        // 某些 WebView/自绘列表只在事件源节点暴露当前持仓行，补采并去重。
        if (texts.isEmpty() && eventSource != null) collectTexts(eventSource, texts, 0)
        val visibleTexts = texts.take(MAX_TEXT_NODES)
        val settings = appContainer.settings
        val configuredPositions = settings.positions()
        val cachedQuotes = withContext(Dispatchers.IO) {
            appContainer.database.cacheDao().getQuotes(settings.allCodes())
        }
        val knownCodeByName = buildMap {
            configuredPositions.filter { it.name.isNotBlank() }.forEach { put(it.name, it.code) }
            cachedQuotes.filter { it.name.isNotBlank() }.forEach { put(it.name, it.code) }
            putAll(resolvedNameCodes)
        }
        val visibleNames = ThsPositionParser.findVisiblePositionNames(visibleTexts)
        val unresolvedNames = visibleNames.filterNot { name -> knownCodeByName.keys.any { it.equals(name, true) } }
        if (unresolvedNames.isNotEmpty() && System.currentTimeMillis() - lastNameResolveAt >= NAME_RESOLVE_INTERVAL_MS) {
            lastNameResolveAt = System.currentTimeMillis()
            val newlyResolved = withContext(Dispatchers.IO) {
                appContainer.marketRepository.resolveCodesByNames(unresolvedNames)
            }
            resolvedNameCodes.putAll(newlyResolved)
        }
        val positions = ThsPositionParser.parse(visibleTexts, knownCodeByName + resolvedNameCodes)
        val trades = ThsTradeParser.parse(visibleTexts)
        val isHoldingTable = ThsPositionParser.isHoldingTable(visibleTexts)
        val isEmptyHoldingTable = ThsPositionParser.isEmptyHoldingTable(visibleTexts)
        val completeHoldingSnapshot = isHoldingTable && (
            isEmptyHoldingTable ||
                (positions.isNotEmpty() && (visibleNames.isEmpty() || positions.size == visibleNames.distinct().size))
            )
        var positionChanged = false
        var accountChanged = false
        var removedPositionCount = 0
        var insertedTrades = 0
        var estimatedTotalAssets: Double? = null
        val parsedPositionRatio = ThsPositionParser.parsePositionRatio(visibleTexts)

        if (completeHoldingSnapshot) {
            // 用户明确选择以同花顺为准；完整快照整体替换，缺失股票视为已卖出并从应用移除。
            val replacement = ThsPositionMerger.replace(configuredPositions, positions)
            removedPositionCount = configuredPositions.count { old -> replacement.none { it.code == old.code } }
            if (replacement != configuredPositions) {
                settings.savePositions(replacement)
                positionChanged = true
            }
            parsedPositionRatio?.let { ratio ->
                if (abs(settings.positionRatio - ratio) > 0.001) {
                    settings.positionRatio = ratio
                    accountChanged = true
                }
            }
            ThsAccountCalculator.estimate(positions, parsedPositionRatio)?.let { estimate ->
                estimatedTotalAssets = estimate.totalAssets
                val now = System.currentTimeMillis()
                // 市值实时跳动时通知可更新，但账户快照最多每 30 秒落盘一次，减少闪存写入。
                if (settings.thsEstimatedTotalAssets == null || now - lastAccountPersistAt >= ACCOUNT_PERSIST_INTERVAL_MS) {
                    if (settings.cashBalance == null || abs(settings.cashBalance!! - estimate.cashBalance) > 0.01) accountChanged = true
                    settings.cashBalance = estimate.cashBalance
                    settings.thsEstimatedTotalAssets = estimate.totalAssets
                    lastAccountPersistAt = now
                }
            }
        }

        if (trades.isNotEmpty()) {
            insertedTrades = withContext(Dispatchers.IO) {
                val dao = appContainer.database.cacheDao()
                val existing = dao.getTradeRecords().toMutableList()
                var inserted = 0
                trades.forEach { trade ->
                    val duplicate = existing.any { row ->
                        row.code == trade.code &&
                            row.side.equals(trade.side, ignoreCase = true) &&
                            row.quantity == trade.quantity &&
                            abs(row.price - trade.price) < 0.0001 &&
                            abs(row.tradeAt - trade.tradeAt) <= 90_000L
                    }
                    if (!duplicate) {
                        val entity = TradeRecordEntity(
                            tradeAt = trade.tradeAt,
                            code = trade.code,
                            side = trade.side,
                            quantity = trade.quantity,
                            price = trade.price,
                            fee = 0.0,
                            source = "THS_ACCESSIBILITY",
                            note = "同花顺可见成交页面同步"
                        )
                        dao.insertTradeRecord(entity)
                        existing += entity
                        inserted++
                    }
                }
                inserted
            }
        }

        val buyCount = trades.count { it.side == "BUY" }
        val sellCount = trades.count { it.side == "SELL" }
        val tradeSummary = "成交 ${trades.size} 条（买入 $buyCount / 卖出 $sellCount）"
        val accountSummary = buildString {
            parsedPositionRatio?.let { append("，仓位${String.format(Locale.CHINA, "%.2f", it)}%") }
            estimatedTotalAssets?.let { append("，估算总资产${String.format(Locale.CHINA, "%.2f", it / 10_000.0)}万元") }
        }
        val message = when {
            positions.isNotEmpty() && trades.isNotEmpty() -> "已识别持仓 ${positions.size} 只、$tradeSummary$accountSummary"
            completeHoldingSnapshot && positions.isNotEmpty() -> buildString {
                append("已按同花顺更新持仓 ${positions.size} 只")
                when {
                    removedPositionCount > 0 -> append("，已清理卖出 $removedPositionCount 只")
                    positionChanged -> append("，配置已更新")
                    else -> append("，数据无变化")
                }
                append(accountSummary)
            }
            completeHoldingSnapshot && isEmptyHoldingTable -> "同花顺当前为空仓，已清空应用持仓$accountSummary"
            positions.isNotEmpty() -> "识别到持仓 ${positions.size} 只，但页面快照不完整，未覆盖应用持仓"
            trades.isNotEmpty() -> "已识别$tradeSummary，本次新增 $insertedTrades 条"
            visibleTexts.isEmpty() -> "已读取同花顺窗口（$packageName），但页面没有可访问文本"
            else -> "已读取同花顺窗口（$packageName），但未识别到持仓或成交字段（文本 ${visibleTexts.size} 项）"
        }
        val recognized = positions.isNotEmpty() || trades.isNotEmpty()
        if (recognized) {
            lastSuccessfulStatus = message
            appContainer.settings.thsLastSuccessMessage = message
            publishStatus(message)
        } else if (userInitiated || lastSuccessfulStatus.isBlank()) {
            // 自动轮询经过同花顺首页、自选页时不覆盖最近一次成功结果；手动点击则明确反馈失败原因。
            publishStatus(message, forceNotification = userInitiated)
        }
        if (positionChanged || accountChanged || insertedTrades > 0) ThsSyncBus.notifyDataChanged()
        android.util.Log.d("ThsTradeSync", "同步扫描完成：$packageName，$message")
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 24 || out.size >= MAX_TEXT_NODES) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) out += text
        // 同一节点的 text 与 contentDescription 经常完全相同，跳过节点级重复，保留表格行顺序。
        if (description.isNotBlank() && description != text) out += description
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collectTexts(child, out, depth + 1) }
            if (out.size >= MAX_TEXT_NODES) break
        }
    }

    override fun onInterrupt() {
        publishStatus("辅助服务被系统中断，等待系统重新连接")
        android.util.Log.i("ThsTradeSync", "Accessibility sync interrupted by the system")
    }

    override fun onDestroy() {
        scheduledScan?.cancel()
        pollingJob?.cancel()
        manualScanJob?.cancel()
        ThsRecognitionNotification.cancel(this)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * MIUI 的部分版本不会稳定派发同花顺窗口事件，因此增加只读轮询兜底。
     * 仅当前台窗口属于同花顺时扫描，离开后自动降频，且不会执行任何节点动作。
     */
    private fun startForegroundWindowPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val activeRoot = findReadableRoot()
                val activePackage = activeRoot?.packageName?.toString().orEmpty()
                val isTonghuashun = activePackage.isNotBlank() && isTonghuashunPackage(activePackage)
                val now = System.currentTimeMillis()
                if (isTonghuashun && now - lastScanAt >= MIN_SCAN_INTERVAL_MS) {
                    lastScanAt = now
                    syncVisibleData(activePackage, null, activeRoot)
                } else if (activeRoot == null) {
                    publishStatus("辅助服务已连接，但系统暂未提供可读取窗口")
                }
                delay(if (isTonghuashun) FOREGROUND_POLL_INTERVAL_MS else BACKGROUND_POLL_INTERVAL_MS)
            }
        }
    }

    /** 消费通知栏“立即识别”按钮；等待通知面板收起后再读取底层同花顺窗口。 */
    private fun observeManualScanRequests() {
        manualScanJob?.cancel()
        manualScanJob = scope.launch {
            ThsScanCommandBus.requests.collect {
                showNotification("正在识别，请保持同花顺页面打开…", force = true)
                val root = waitForTonghuashunRoot()
                if (root == null) {
                    publishStatus("手动识别失败：请先打开同花顺持仓或成交页面，再点击立即识别", forceNotification = true)
                } else {
                    val packageName = root.packageName?.toString().orEmpty()
                    lastScanAt = System.currentTimeMillis()
                    lastNotificationStatus = ""
                    syncVisibleData(packageName, null, root, userInitiated = true)
                }
            }
        }
    }

    /** 通知面板关闭在不同系统上有延迟，最多等待约 1.8 秒寻找同花顺活动窗口。 */
    private suspend fun waitForTonghuashunRoot(): AccessibilityNodeInfo? {
        repeat(8) {
            delay(500)
            val root = findReadableRoot()
            if (root != null && isTonghuashunPackage(root.packageName?.toString().orEmpty())) {
                val preview = ArrayList<String>(32)
                collectTexts(root, preview, 0)
                val hasTargetMarker = preview.any { text ->
                    text.contains("持仓") || text.contains("成本") || text.contains("成交") ||
                        text.contains("买入") || text.contains("卖出") || text.contains("交割")
                }
                // 通知面板刚关闭时可能只有少量根节点，必须等业务页面树恢复后才扫描。
                if (preview.size >= MIN_MANUAL_TEXT_NODES && hasTargetMarker) return root
            }
        }
        return null
    }

    /** 同步持久化状态并刷新通知；相同自动结果不重复刷新状态栏。 */
    private fun publishStatus(message: String, forceNotification: Boolean = false) {
        appContainer.settings.recordThsSync(message)
        showNotification(message, forceNotification)
    }

    private fun showNotification(message: String, force: Boolean = false) {
        val settings = appContainer.settings
        if (!settings.thsStatusNotificationEnabled) {
            ThsRecognitionNotification.cancel(this)
            return
        }
        if (!force && message == lastNotificationStatus) return
        lastNotificationStatus = message
        ThsRecognitionNotification.show(this, message)
    }

    /** MIUI 偶尔令 rootInActiveWindow 为空，改从交互窗口列表寻找活动或聚焦窗口。 */
    private fun findReadableRoot(): AccessibilityNodeInfo? = rootInActiveWindow
        ?: windows.firstOrNull { it.isActive }?.root
        ?: windows.firstOrNull { it.isFocused }?.root

    /** 同花顺存在渠道包和应用分身：优先包名判断，改包名时再用安装应用标签兜底。 */
    private fun isTonghuashunPackage(packageName: String): Boolean = packageMatchCache.getOrPut(packageName) {
        if (packageName.contains("hexin", ignoreCase = true)) return@getOrPut true
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().contains("同花顺")
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_TEXT_NODES = 800
        private const val MIN_MANUAL_TEXT_NODES = 10
        private const val MIN_SCAN_INTERVAL_MS = 1_200L
        private const val FOREGROUND_POLL_INTERVAL_MS = 2_000L
        private const val BACKGROUND_POLL_INTERVAL_MS = 6_000L
        private const val NAME_RESOLVE_INTERVAL_MS = 5 * 60_000L
        private const val ACCOUNT_PERSIST_INTERVAL_MS = 30_000L
    }
}
