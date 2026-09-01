package com.locogo.astockguard.data.ifind

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
