package com.locogo.astockguard.chart

data class MinuteCandle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val average: Double,
    val volume: Double
)
