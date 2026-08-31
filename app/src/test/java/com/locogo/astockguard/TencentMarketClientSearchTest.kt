package com.locogo.astockguard

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
