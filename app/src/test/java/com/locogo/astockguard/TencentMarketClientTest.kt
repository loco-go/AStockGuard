package com.locogo.astockguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TencentMarketClientTest {
    @Test
    fun preservesExchangePrefixAndUsesExactYuanAmount() {
        val text = """
            v_sh000001="1~上证指数~000001~3957.54~3912.52~3911.89~483474868~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~20260827144833~45.02~1.15~3958.03~3909.31~3957.54/483474868/949926957404~483474868~94992696";
            v_sz399001="51~深证成指~399001~14049.67~13841.33~13876.79~571657018~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~20260827144830~208.34~1.51~14049.67~13808.62~14049.67/571657018/1044437655406~571657018~104443766";
        """.trimIndent()

        val quotes = TencentMarketClient().parse(text)

        assertEquals(listOf("000001.SH", "399001.SZ"), quotes.map { it.code })
        assertEquals(949_926_957_404.0, quotes[0].amount ?: 0.0, 0.0)
        assertEquals(1_044_437_655_406.0, quotes[1].amount ?: 0.0, 0.0)
    }

    @Test
    fun preOpenZeroPriceDoesNotBecomeRealQuoteOrChangeRatio() {
        // 腾讯在集合竞价前可能把现价、涨幅暂时置零，昨收仍可用于安全估值。
        val text = """
            v_sh600522="1~中天科技~600522~0~10.50~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~20260902090000~0~0~0~0/0/0~0~0";
        """.trimIndent()

        val quote = TencentMarketClient().parse(text).single()

        assertNull(quote.latest)
        assertNull(quote.changeRatio)
        assertEquals(10.50, quote.previousClose ?: 0.0, 0.0)
    }
}
