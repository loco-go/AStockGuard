package com.locogo.astockguard.domain.review

import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.TradeRecordEntity
import java.time.Instant
import java.time.ZoneId

class ReviewRepository(
    private val dao: CacheDao,
    private val marketRepository: MarketRepository
) {
    suspend fun signalStats(limit: Int = 100): SignalReviewStats {
        val events = dao.getSignalEvents(limit).filter { it.price > 0 && it.action.uppercase() in ACTIONS }
        if (events.isEmpty()) return SignalReviewStats()
        val items = events.map { event ->
            val date = Instant.ofEpochMilli(event.eventAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val future = runCatching { marketRepository.loadDailyBars(event.code, 30) }.getOrDefault(emptyList())
                .firstOrNull { it.date > date }
            val futurePrice = future?.close
            val edge = if (futurePrice == null || event.price <= 0) null else directionalEdge(event.action, event.price, futurePrice)
            SignalReviewItem(
                code = event.code,
                eventAt = event.eventAt,
                action = event.action,
                signalPrice = event.price,
                futurePrice = futurePrice,
                edgePct = edge,
                success = edge?.let { it > 0 },
                reason = event.reason
            )
        }
        val evaluated = items.filter { it.edgePct != null }
        val wins = evaluated.count { it.success == true }
        return SignalReviewStats(
            total = items.size,
            evaluated = evaluated.size,
            wins = wins,
            winRate = if (evaluated.isEmpty()) 0.0 else wins * 100.0 / evaluated.size,
            averageEdgePct = evaluated.mapNotNull { it.edgePct }.averageOrZero(),
            recent = items.take(20)
        )
    }

    suspend fun recordTrade(code: String, side: String, quantity: Int, price: Double, fee: Double = 0.0, note: String = "", source: String = "USER") {
        require(quantity > 0 && price > 0) { "数量和价格必须大于0" }
        require(side.uppercase() in setOf("BUY", "SELL")) { "side must be BUY or SELL" }
        dao.insertTradeRecord(TradeRecordEntity(
            tradeAt = System.currentTimeMillis(), code = code, side = side.uppercase(), quantity = quantity,
            price = price, fee = fee.coerceAtLeast(0.0), source = source, note = note
        ))
    }

    suspend fun tradeStats(): TradeReviewStats {
        val trades = dao.getTradeRecords().sortedBy { it.tradeAt }
        data class Lot(var qty: Int, val price: Double)
        val lots = mutableMapOf<String, ArrayDeque<Lot>>()
        var realized = 0.0
        var closed = 0
        var wins = 0
        var fees = 0.0
        trades.forEach { trade ->
            fees += trade.fee
            val queue = lots.getOrPut(trade.code) { ArrayDeque() }
            if (trade.side == "BUY") {
                queue.addLast(Lot(trade.quantity, trade.price))
            } else {
                var remaining = trade.quantity
                var pnlThisSell = 0.0
                var matched = 0
                while (remaining > 0 && queue.isNotEmpty()) {
                    val lot = queue.first()
                    val qty = minOf(remaining, lot.qty)
                    pnlThisSell += (trade.price - lot.price) * qty
                    matched += qty
                    remaining -= qty
                    lot.qty -= qty
                    if (lot.qty == 0) queue.removeFirst()
                }
                if (matched > 0) {
                    closed++
                    realized += pnlThisSell
                    if (pnlThisSell > 0) wins++
                }
            }
        }
        realized -= fees
        return TradeReviewStats(
            trades = trades.size, closedTrades = closed, wins = wins,
            winRate = if (closed == 0) 0.0 else wins * 100.0 / closed,
            realizedPnl = realized, totalFees = fees
        )
    }

    private fun directionalEdge(action: String, entry: Double, future: Double): Double = when (action.uppercase()) {
        "BUY", "R2" -> (future / entry - 1.0) * 100.0
        else -> (entry / future - 1.0) * 100.0
    }

    private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
    companion object { private val ACTIONS = setOf("BUY", "R2", "SELL", "REDUCE", "REVIEW") }
}
