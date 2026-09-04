package com.locogo.astockguard.domain.review

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.local.AlertRecordEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* 文件职责：验证量能提醒只在观察窗口完成后评价，并按不同分类使用正确方向。 */

class IntradayVolumeSignalEvaluatorTest {
    /** BULL_TRAP后30分钟下跌超过2%应评价为有效。 */
    @Test
    fun bullTrapDroppingMoreThanTwoPercentInThirtyMinutesIsEffective() {
        val record = alert("BULL_TRAP", "SELL_T", "10:00", 100.0)
        val bars = listOf(bar("10:00", 100.0)) + (1..30).map { minute ->
            bar("10:${minute.toString().padStart(2, '0')}", 100.0 - minute * 0.08)
        }

        val result = IntradayVolumeSignalEvaluator.evaluate(record, bars, 1, 30, 2_000L)!!

        assertTrue(result.effective)
        assertTrue(result.returnPct < -2.0)
    }

    /** REAL_BREAKOUT后30分钟继续上涨超过2%应评价为有效。 */
    @Test
    fun realBreakoutContinuingMoreThanTwoPercentIsEffective() {
        val record = alert("REAL_BREAKOUT", "HOLD", "10:00", 100.0)
        val bars = listOf(bar("10:00", 100.0)) + (1..30).map { minute ->
            bar("10:${minute.toString().padStart(2, '0')}", 100.0 + minute * 0.08)
        }

        val result = IntradayVolumeSignalEvaluator.evaluate(record, bars, 1, 30, 2_000L)!!

        assertTrue(result.effective)
        assertTrue(result.returnPct > 2.0)
    }

    /** 未来分钟数尚未走完时必须返回null，不能用不完整样本提前判定失败。 */
    @Test
    fun incompleteHorizonRemainsPending() {
        val record = alert("BULL_TRAP", "SELL_T", "10:00", 100.0)
        val bars = listOf(bar("10:00", 100.0), bar("10:01", 99.0))

        val result = IntradayVolumeSignalEvaluator.evaluate(record, bars, 1, 30, 2_000L)

        assertNull(result)
    }

    /** BULL_TRAP后反而上涨时方向收益为负，应评价为无效。 */
    @Test
    fun bullTrapFollowedByRiseIsNotEffective() {
        val record = alert("BULL_TRAP", "SELL_T", "10:00", 100.0)
        val bars = listOf(bar("10:00", 100.0)) + (1..5).map { bar("10:0$it", 100.0 + it * 0.2) }

        val result = IntradayVolumeSignalEvaluator.evaluate(record, bars, 1, 5, 2_000L)!!

        assertFalse(result.effective)
    }

    /** 构造一条已持久化雷达提醒，id固定用于验证子表外键式关联字段。 */
    private fun alert(type: String, action: String, time: String, price: Double) = AlertRecordEntity(
        id = 7L, alertKey = "test", code = "000001.SZ", name = "测试", signalAt = 1L,
        signalDate = "2026-09-04", signalTime = time, action = action, price = price, score = 80,
        strategyVersion = "VOLUME_RADAR_V1", source = "TEST", dataSource = "TEST", reason = "测试",
        alertType = "VOLUME_RADAR", signalType = type, confidence = 80
    )

    /** 创建一分钟行情，高低价围绕收盘价设置，供MFE和MAE同步验证。 */
    private fun bar(time: String, price: Double) = MinuteBar(
        time, price, price, price + 0.05, price - 0.05, 100.0, price * 100.0
    )
}
