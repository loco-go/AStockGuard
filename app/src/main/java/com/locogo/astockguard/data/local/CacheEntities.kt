package com.locogo.astockguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.Quote
import com.locogo.astockguard.MinuteBar

@Entity(tableName = "quote_cache")
data class QuoteCacheEntity(
    @PrimaryKey val code: String,
    val name: String,
    val time: String,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val latest: Double?,
    val previousClose: Double?,
    val changeRatio: Double?,
    val volume: Double?,
    val amount: Double?,
    val vwap: Double?,
    val ma5: Double?,
    val ma10: Double?,
    val r2Score: Int,
    val r2Grade: String,
    val r2Reason: String,
    val cachedAt: Long
)

@Entity(tableName = "daily_bar_cache", primaryKeys = ["code", "date"])
data class DailyBarCacheEntity(
    val code: String,
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val cachedAt: Long
)

@Entity(tableName = "ai_analysis")
data class AiAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val prompt: String,
    val rawAnswer: String,
    val marketAction: String,
    val confidence: Int,
    val targetPositionPct: Int,
    val strategyJson: String
)

fun Quote.toCacheEntity(now: Long = System.currentTimeMillis()) = QuoteCacheEntity(
    code, name, time, open, high, low, latest, previousClose, changeRatio, volume, amount,
    vwap, ma5, ma10, r2Score, r2Grade, r2Reason, now
)

fun QuoteCacheEntity.toModel() = Quote(
    code, name, time, open, high, low, latest, previousClose, changeRatio, volume, amount,
    vwap, ma5, ma10, r2Score, r2Grade, r2Reason
)

fun DailyBar.toCacheEntity(code: String, now: Long = System.currentTimeMillis()) =
    DailyBarCacheEntity(code, date, open, close, high, low, volume, now)

fun DailyBarCacheEntity.toModel() = DailyBar(date, open, close, high, low, volume)

@Entity(tableName = "minute_bar_cache", primaryKeys = ["code", "time"])
data class MinuteBarCacheEntity(
    val code: String,
    val time: String,
    val price: Double,
    val avgPrice: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double,
    val cachedAt: Long
)

fun MinuteBar.toCacheEntity(code: String, now: Long = System.currentTimeMillis()) =
    MinuteBarCacheEntity(code, time, price, avgPrice, high, low, volume, amount, now)

fun MinuteBarCacheEntity.toModel() = MinuteBar(time, price, avgPrice, high, low, volume, amount)
