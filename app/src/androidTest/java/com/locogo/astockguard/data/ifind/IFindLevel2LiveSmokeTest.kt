package com.locogo.astockguard.data.ifind

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
