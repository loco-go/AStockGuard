package com.locogo.astockguard.ui.chart

import com.locogo.astockguard.DailyBar
import org.json.JSONArray
import org.json.JSONObject

object KLineHtmlBuilder {
    fun build(bars: List<DailyBar>): String {
        val data = JSONArray()
        bars.forEach {
            data.put(JSONObject().apply {
                put("timestamp", it.date)
                put("open", it.open)
                put("high", it.high)
                put("low", it.low)
                put("close", it.close)
                put("volume", it.volume)
            })
        }
        return """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<script src="https://cdn.jsdelivr.net/npm/klinecharts/dist/umd/klinecharts.min.js"></script>
<style>html,body,#chart{width:100%;height:100%;margin:0}</style>
</head>
<body>
<div id="chart"></div>
<script>
const chart=klinecharts.init('chart');
chart.applyNewData($data);
chart.createIndicator('MA');
chart.createIndicator('VOL');
</script>
</body>
</html>
""".trimIndent().replace("$data", data.toString())
    }
}
