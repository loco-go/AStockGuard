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
import kotlin.math.abs

/**
 * Read-only Tonghuashun trade synchronizer.
 *
 * It never performs accessibility actions/clicks and never stores raw screen text. The user
 * opens the trade-result page manually; this service only extracts visible trade fields and
 * persists normalized TradeRecord rows for chart markers/review.
 */
class ThsTradeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        val pkg = currentEvent.packageName?.toString().orEmpty()
        if (pkg !in SUPPORTED_PACKAGES) return
        if (currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(900)
            syncVisibleTrades()
        }
    }

    private suspend fun syncVisibleTrades() {
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(128)
        collectTexts(root, texts, 0)
        val parsed = ThsTradeParser.parse(texts)
        if (parsed.isEmpty()) return

        val dao = appContainer.database.cacheDao()
        val existing = dao.getTradeRecords()
        parsed.forEach { trade ->
            val duplicate = existing.any { row ->
                row.code == trade.code &&
                    row.side.equals(trade.side, ignoreCase = true) &&
                    row.quantity == trade.quantity &&
                    abs(row.price - trade.price) < 0.0001 &&
                    abs(row.tradeAt - trade.tradeAt) <= 90_000L
            }
            if (!duplicate) {
                dao.insertTradeRecord(
                    TradeRecordEntity(
                        tradeAt = trade.tradeAt,
                        code = trade.code,
                        side = trade.side,
                        quantity = trade.quantity,
                        price = trade.price,
                        fee = 0.0,
                        source = "THS_ACCESSIBILITY",
                        note = "同花顺可见成交页面同步"
                    )
                )
            }
        }
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
        private const val MAX_TEXT_NODES = 600
        private val SUPPORTED_PACKAGES = setOf(
            "com.hexin.plat.android",
            "com.hexin.plat.android.supremacy"
        )
    }
}
