package com.locogo.astockguard.chart

object TechnicalIndicators {

    fun ma(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0) return emptyList()
        return values.mapIndexed { index, _ ->
            if (index + 1 < period) {
                null
            } else {
                values.subList(index + 1 - period, index + 1).average()
            }
        }
    }

    fun volumeRatio(current: Double, average: Double): Double {
        if (average <= 0) return 0.0
        return current / average
    }
}
