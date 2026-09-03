package com.locogo.astockguard.ui.chart

/*
 * 文件职责：验证 KLineHtmlBuilder 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.chart.MinuteCandle
import org.junit.Assert.assertTrue
import org.junit.Test

class KLineHtmlBuilderTest {
    @Test
    fun dailyCandlesExplicitlyCompareCloseWithCurrentOpen() {
        val html = KLineHtmlBuilder.build(
            listOf(DailyBar("2026-09-01", 10.0, 10.5, 10.6, 9.9, 1000.0))
        )

        assertTrue(html.contains("compareRule:'current_open'"))
        assertTrue(html.contains("upColor:'#d92d20'"))
        assertTrue(html.contains("downColor:'#039855'"))
        assertTrue(html.contains("noChangeColor:'#8a8f98'"))
    }

    @Test
    fun minuteCandlesUseNeutralColorWhenCloseEqualsOpen() {
        val html = KLineHtmlBuilder.buildMinute(
            listOf(MinuteCandle("09:30", 10.0, 10.1, 9.9, 10.0, 10.0, 100.0))
        )

        assertTrue(html.contains("r.close>r.open?'#d92d20'"))
        assertTrue(html.contains("r.close<r.open?'#039855':'#8a8f98'"))
    }
}
