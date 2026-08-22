package com.locogo.astockguard.domain.paper

import androidx.room.withTransaction
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.data.local.PaperAccountEntity
import com.locogo.astockguard.data.local.PaperEquityEntity
import com.locogo.astockguard.data.local.PaperOrderEntity
import com.locogo.astockguard.data.local.PaperPositionEntity

data class PaperPositionView(
    val code: String,
    val quantity: Int,
    val avgCost: Double,
    val latest: Double?,
    val marketValue: Double,
    val unrealizedPnl: Double
)

data class PaperSummary(
    val initialCash: Double = 100_000.0,
    val cash: Double = 100_000.0,
    val marketValue: Double = 0.0,
    val equity: Double = 100_000.0,
    val returnPct: Double = 0.0,
    val positions: List<PaperPositionView> = emptyList(),
    val recentOrders: List<PaperOrderEntity> = emptyList()
)

class PaperTradingRepository(private val database: AStockDatabase) {
    private val dao get() = database.cacheDao()

    suspend fun reset(initialCash: Double = 100_000.0) {
        require(initialCash > 0) { "初始资金必须大于0" }
        database.withTransaction {
            dao.clearPaperOrders()
            dao.clearPaperPositions()
            dao.clearPaperEquity()
            dao.upsertPaperAccount(PaperAccountEntity(initialCash = initialCash, cash = initialCash, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun ensureAccount(initialCash: Double = 100_000.0): PaperAccountEntity {
        dao.getPaperAccount()?.let { return it }
        val account = PaperAccountEntity(initialCash = initialCash, cash = initialCash, updatedAt = System.currentTimeMillis())
        dao.upsertPaperAccount(account)
        return account
    }

    suspend fun execute(code: String, side: String, quantity: Int, price: Double, source: String = "MANUAL", note: String = ""): PaperOrderEntity {
        require(quantity > 0) { "数量必须大于0" }
        require(price > 0 && price.isFinite()) { "成交价无效" }
        val normalizedSide = side.uppercase()
        require(normalizedSide == "BUY" || normalizedSide == "SELL") { "side必须为BUY/SELL" }
        val now = System.currentTimeMillis()
        val order = database.withTransaction {
            val account = ensureAccount()
            val position = dao.getPaperPosition(code)
            val fee = 0.0 // 研究模拟默认不假定当前券商费率；后续可配置费率模型。
            if (normalizedSide == "BUY") {
                val cost = price * quantity + fee
                require(account.cash + 1e-6 >= cost) { "模拟账户可用资金不足" }
                val oldQty = position?.quantity ?: 0
                val oldCost = position?.avgCost ?: 0.0
                val newQty = oldQty + quantity
                val newAvg = if (newQty == 0) 0.0 else (oldCost * oldQty + price * quantity + fee) / newQty
                dao.upsertPaperPosition(PaperPositionEntity(code, newQty, newAvg, now))
                dao.upsertPaperAccount(account.copy(cash = account.cash - cost, updatedAt = now))
            } else {
                val oldQty = position?.quantity ?: 0
                require(oldQty >= quantity) { "模拟持仓不足" }
                val remain = oldQty - quantity
                if (remain == 0) dao.deletePaperPosition(code)
                else dao.upsertPaperPosition(position!!.copy(quantity = remain, updatedAt = now))
                dao.upsertPaperAccount(account.copy(cash = account.cash + price * quantity - fee, updatedAt = now))
            }
            val filled = PaperOrderEntity(
                createdAt = now, code = code, side = normalizedSide, quantity = quantity,
                price = price, fee = fee, status = "FILLED", source = source, note = note
            )
            val id = dao.insertPaperOrder(filled)
            filled.copy(id = id)
        }
        return order
    }

    suspend fun summary(quotes: List<Quote>): PaperSummary {
        val account = ensureAccount()
        val quoteMap = quotes.associateBy { it.code }
        val positions = dao.getPaperPositions().map { p ->
            val latest = quoteMap[p.code]?.latest
            val marketValue = (latest ?: p.avgCost) * p.quantity
            PaperPositionView(
                code = p.code,
                quantity = p.quantity,
                avgCost = p.avgCost,
                latest = latest,
                marketValue = marketValue,
                unrealizedPnl = ((latest ?: p.avgCost) - p.avgCost) * p.quantity
            )
        }
        val marketValue = positions.sumOf { it.marketValue }
        val equity = account.cash + marketValue
        val returnPct = if (account.initialCash > 0) (equity / account.initialCash - 1.0) * 100.0 else 0.0
        dao.insertPaperEquity(PaperEquityEntity(recordedAt = System.currentTimeMillis(), equity = equity, cash = account.cash, marketValue = marketValue))
        return PaperSummary(
            initialCash = account.initialCash,
            cash = account.cash,
            marketValue = marketValue,
            equity = equity,
            returnPct = returnPct,
            positions = positions,
            recentOrders = dao.getPaperOrders(20)
        )
    }
}
