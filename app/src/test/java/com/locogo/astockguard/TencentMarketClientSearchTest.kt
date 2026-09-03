package com.locogo.astockguard

/*
 * 文件职责：验证 TencentMarketClientSearch 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class TencentMarketClientSearchTest {
    @Test
    fun `解析腾讯证券名称搜索候选`() {
        val response = "v_hint=\"sh~600522~\\u4e2d\\u5929\\u79d1\\u6280~ztkj~GP-A^sz~000001~name~payh~GP-A\""

        assertEquals(
            listOf("600522.SH", "000001.SZ"),
            TencentMarketClient.parseSearchCodes(response)
        )
    }
}
