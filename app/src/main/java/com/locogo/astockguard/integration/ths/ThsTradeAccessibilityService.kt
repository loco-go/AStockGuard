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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var lastScanAt = 0L
    private var lastNameResolveAt = 0L
    private val packageMatchCache = mutableMapOf<String, Boolean>()
    private val resolvedNameCodes = mutableMapOf<String, String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 授权开关不等于系统已成功绑定；记录此状态方便在同步页直接判断服务是否真正运行。
        appContainer.settings.recordThsSync("辅助服务已连接，等待打开同花顺持仓页")
        android.util.Log.i("ThsTradeSync", "Accessibility service connected")
        startForegroundWindowPolling()
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
        rootOverride: AccessibilityNodeInfo? = null
    ) {
        val root = rootOverride ?: findReadableRoot()
        if (root == null && eventSource == null) {
            appContainer.settings.recordThsSync("检测到同花顺，但暂时无法读取当前窗口，请停留在持仓页后重试")
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
        var positionChanged = false
        var insertedTrades = 0

        if (positions.isNotEmpty()) {
            val merged = ThsPositionMerger.merge(configuredPositions, positions)
            if (merged != configuredPositions) {
                settings.savePositions(merged)
                positionChanged = true
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

        val message = when {
            positions.isNotEmpty() && trades.isNotEmpty() -> "已识别持仓 ${positions.size} 只、成交 ${trades.size} 条"
            positions.isNotEmpty() -> "已识别可见持仓 ${positions.size} 只${if (positionChanged) "，配置已更新" else "，数据无变化"}"
            trades.isNotEmpty() -> "已识别成交 ${trades.size} 条，本次新增 $insertedTrades 条"
            visibleTexts.isEmpty() -> "已读取同花顺窗口（$packageName），但页面没有可访问文本"
            else -> "已读取同花顺窗口（$packageName），但未识别到持仓或成交字段（文本 ${visibleTexts.size} 项）"
        }
        appContainer.settings.recordThsSync(message)
        if (positionChanged || insertedTrades > 0) ThsSyncBus.notifyDataChanged()
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
        android.util.Log.i("ThsTradeSync", "Accessibility sync interrupted by the system")
    }

    override fun onDestroy() {
        scheduledScan?.cancel()
        pollingJob?.cancel()
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
                    appContainer.settings.recordThsSync("辅助服务已连接，但系统暂未提供可读取窗口")
                }
                delay(if (isTonghuashun) FOREGROUND_POLL_INTERVAL_MS else BACKGROUND_POLL_INTERVAL_MS)
            }
        }
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
        private const val MIN_SCAN_INTERVAL_MS = 1_200L
        private const val FOREGROUND_POLL_INTERVAL_MS = 2_000L
        private const val BACKGROUND_POLL_INTERVAL_MS = 6_000L
        private const val NAME_RESOLVE_INTERVAL_MS = 5 * 60_000L
    }
}
