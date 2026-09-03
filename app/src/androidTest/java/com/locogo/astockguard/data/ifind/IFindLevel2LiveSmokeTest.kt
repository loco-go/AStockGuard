package com.locogo.astockguard.data.ifind

/*
 * 文件职责：在真实 Android 环境中按显式配置执行 iFinD Level-2 冒烟验证，确认代码、十档深度和时间字段符合契约。
 * 运行边界：测试依赖用户主动提供的有效 Token；缺少凭据时应跳过，凭据不得写入源码、日志或测试报告。
 * 风险说明：网络冒烟只验证数据接入，不证明盘口信号能够盈利，也不应成为普通 CI 的硬依赖。
 */

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locogo.astockguard.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用测试手机本机Keystore中的refresh token验证真实iFinD十档权限。
 * CI没有人工配置Token时自动跳过；测试结果不输出Token和接口原始响应。
 */
@RunWith(AndroidJUnit4::class)
class IFindLevel2LiveSmokeTest {
    @Test
    fun authorizedAccountReturnsOrderBookLevels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context)
        assumeTrue("本机未配置iFinD refresh token", settings.ifindRefreshToken.isNotBlank())
        val code = settings.allCodes().firstOrNull() ?: "000001.SZ"

        val snapshot = IFindHttpClient(settings).fetchLevel2Snapshot(code)

        assertEquals(
            "iFinD盘口不是完整十档：买${snapshot.bids.size}档/卖${snapshot.asks.size}档",
            10 to 10,
            snapshot.bids.size to snapshot.asks.size
        )
        assertTrue("盘口来源标记错误", snapshot.source == "IFIND_HTTP_LEVEL2")
        assertTrue("服务端盘口时间无效", snapshot.updatedAt > 0L)
    }
}
