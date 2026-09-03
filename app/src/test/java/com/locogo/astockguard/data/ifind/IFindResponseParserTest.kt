package com.locogo.astockguard.data.ifind

/*
 * 文件职责：验证 IFindResponseParser 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IFindResponseParserTest {
    @Test
    fun parsesOfficialTablesAndIndicatorArrays() {
        val root = JSONObject(
            """{
              "errorcode": 0,
              "tables": [{
                "thscode": "600000.SH",
                "table": {
                  "tradeTime": ["2026-09-01 10:01:02"],
                  "preClose": [10.00], "open": [10.10], "high": [10.30], "low": [9.98],
                  "latest": [10.20], "avgPrice": [10.15], "changeRatio": [2.0],
                  "volume": [123456], "amount": [1253000]
                }
              }]
            }"""
        )

        val quote = IFindResponseParser.parseQuotes(root).single()

        assertEquals("600000.SH", quote.code)
        assertEquals(10.20, quote.latest!!, 0.0001)
        assertEquals(2.0, quote.changeRatio!!, 0.0001)
        assertEquals("2026-09-01 10:01:02", quote.time)
    }

    @Test
    fun acceptsScalarValuesAndMissingOptionalIndicators() {
        val root = JSONObject(
            """{"tables":[{"thscode":"000001.SZ","table":{"latest":12.3,"preClose":"--"}}]}"""
        )

        val quote = IFindResponseParser.parseQuotes(root).single()

        assertEquals(12.3, quote.latest!!, 0.0001)
        assertNull(quote.previousClose)
    }

    @Test
    fun preOpenZeroPriceDoesNotBecomeRealQuoteOrChange() {
        val root = JSONObject(
            """{"tables":[{"thscode":"000001.SZ","table":{"latest":[0],"preClose":[10.5],"changeRatio":[0]}}]}"""
        )

        val quote = IFindResponseParser.parseQuotes(root).single()

        assertNull(quote.latest)
        assertNull(quote.changeRatio)
        assertEquals(10.5, quote.previousClose ?: 0.0, 0.0001)
    }

    @Test
    fun parsesMinuteBarsWithOuterTimeArray() {
        val root = JSONObject(
            """{
              "tables":[{
                "thscode":"600000.SH",
                "time":["2026-09-01 09:30:00","2026-09-01 09:31:00"],
                "table":{
                  "open":[10.0,10.1],"high":[10.2,10.3],"low":[9.9,10.0],
                  "close":[10.1,10.2],"volume":[100,200],"amount":[1010,2040]
                }
              }]
            }"""
        )

        val bars = IFindResponseParser.parseMinuteBars(root)

        assertEquals(2, bars.size)
        assertEquals("09:30", bars[0].time)
        assertEquals(10.2, bars[1].price, 0.0001)
        assertEquals((10.1 * 100 + 10.2 * 200) / 300, bars[1].avgPrice, 0.0001)
    }

    @Test
    fun parsesAndSortsDailyBars() {
        val root = JSONObject(
            """{
              "tables":[{
                "thscode":"000001.SZ",
                "time":["2026-09-01","2026-08-31"],
                "table":{
                  "open":[12.0,11.0],"high":[12.5,11.5],"low":[11.8,10.8],
                  "close":[12.3,11.3],"volume":[3000,2000]
                }
              }]
            }"""
        )

        val bars = IFindResponseParser.parseDailyBars(root)

        assertEquals(listOf("2026-08-31", "2026-09-01"), bars.map { it.date })
        assertEquals(12.3, bars.last().close, 0.0001)
    }

    @Test
    fun parsesAuctionSnapshotAndDropsInvalidPrice() {
        val root = JSONObject(
            """{
              "tables":[{
                "thscode":"600000.SH",
                "time":["2026-09-01 09:15:03","2026-09-01 09:20:00","2026-09-01 09:25:00"],
                "table":{"latest":[10.0,"--",10.2],"volume":[100,200,500],"amount":[1000,2000,5100]}
              }]
            }"""
        )

        val ticks = IFindResponseParser.parseAuctionTicks(root)

        assertEquals(listOf("09:15", "09:25"), ticks.map { it.time })
        assertEquals(500.0, ticks.last().volume, 0.0001)
    }

    @Test
    fun parsesOfficialTenLevelOrderBookWithoutInventingTrades() {
        val root = JSONObject(
            """{"tables":[{"thscode":"000001.SZ","table":{
              "tradeTime":["2026-09-01 10:20:01"],
              "bid1":[10.00],"bidSize1":[12000],"bid2":[9.99],"bidSize2":[8000],
              "ask1":[10.01],"askSize1":[6000],"ask2":[10.02],"askSize2":[9000]
            }}]}"""
        )

        val snapshot = IFindResponseParser.parseLevel2Snapshot(root, "000001.SZ", 1_788_229_201_000L)

        assertEquals("IFIND_HTTP_DEPTH_LIMITED", snapshot.source)
        assertEquals(2, snapshot.bids.size)
        assertEquals(12_000L, snapshot.bids.first().volume)
        assertEquals(2, snapshot.asks.size)
        assertTrue(snapshot.trades.isEmpty())
    }
}
