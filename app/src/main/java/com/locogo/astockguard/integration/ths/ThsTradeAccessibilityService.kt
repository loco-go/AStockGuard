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
    private var debounceJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        val pkg = currentEvent.packageName?.toString().orEmpty()
        if (!isTonghuashunPackage(pkg)) return
        if (currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            currentEvent.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            // 等待列表布局稳定，减少一次页面刷新触发多次解析和写库。
            delay(650)
            syncVisibleData(pkg)
        }
    }

    /** 扫描当前窗口，并分别同步持仓与成交；原始节点文本只在内存中短暂存在。 */
    private suspend fun syncVisibleData(packageName: String) {
        val root = rootInActiveWindow
        if (root == null) {
            appContainer.settings.recordThsSync("检测到同花顺，但暂时无法读取当前窗口，请停留在持仓页后重试")
            return
        }
        // 延迟期间用户可能已经切换应用，避免把其他窗口误当作同花顺页面解析。
        if (!isTonghuashunPackage(root.packageName?.toString().orEmpty())) return
        val texts = ArrayList<String>(128)
        collectTexts(root, texts, 0)
        val positions = ThsPositionParser.parse(texts)
        val trades = ThsTradeParser.parse(texts)
        var positionChanged = false
        var insertedTrades = 0

        if (positions.isNotEmpty()) {
            val settings = appContainer.settings
            val existing = settings.positions()
            val merged = ThsPositionMerger.merge(existing, positions)
            if (merged != existing) {
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
            texts.isEmpty() -> "检测到同花顺，但当前页面没有可访问文本"
            else -> "检测到同花顺页面，但未识别到持仓或成交字段（可访问文本 ${texts.size} 项）"
        }
        appContainer.settings.recordThsSync(message)
        if (positionChanged || insertedTrades > 0) ThsSyncBus.notifyDataChanged()
        android.util.Log.d("ThsTradeSync", "同步扫描完成：$packageName，$message")
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 24 || out.size >= MAX_TEXT_NODES) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collectTexts(child, out, depth + 1) }
            if (out.size >= MAX_TEXT_NODES) break
        }
    }

    override fun onInterrupt() {
        android.util.Log.i("ThsTradeSync", "Accessibility sync interrupted by the system")
    }

    override fun onDestroy() {
        debounceJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_TEXT_NODES = 800

        /** 同花顺存在多个渠道包，使用官方包名前缀兼容，而不是只匹配两个固定版本。 */
        private fun isTonghuashunPackage(packageName: String): Boolean = packageName.startsWith("com.hexin.")
    }
}
