package com.locogo.astockguard

/*
 * 文件职责：验证 EastMoneyMinuteHistoryClient 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EastMoneyMinuteHistoryClientTest {
    @Test
    fun parsesRequestedDateAndBuildsRunningAveragePrice() {
        val rows = listOf(
            "2026-08-26 15:00,9.9,10.0,10.1,9.8,20,20000,0,0,0,0",
            "2026-08-27 09:35,10.0,10.2,10.3,9.9,100,101000,0,0,0,0",
            "2026-08-27 09:40,10.2,10.4,10.5,10.1,50,52000,0,0,0,0"
        )

        val bars = EastMoneyMinuteHistoryClient().parseRows(rows, LocalDate.parse("2026-08-27"))

        assertEquals(2, bars.size)
        assertEquals("09:35", bars.first().time)
        assertEquals(10.3, bars.first().high, 0.0)
        assertEquals(10.1, bars.first().avgPrice, 0.0001)
    }
}
