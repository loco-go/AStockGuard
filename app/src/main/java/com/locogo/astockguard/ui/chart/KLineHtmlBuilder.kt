package com.locogo.astockguard.ui.chart

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.chart.StockKLine
import org.json.JSONArray
import org.json.JSONObject

object KLineHtmlBuilder {
    fun build(bars: List<DailyBar>): String = buildCandles(ChartDataMapper.mapDaily(bars))

    fun buildCandles(candles: List<StockKLine>): String {
        val data = JSONArray()
        candles.forEach {
            data.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("open", it.open)
                put("high", it.high)
                put("low", it.low)
                put("close", it.close)
                put("volume", it.volume)
            })
        }
        return """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<script src="https://cdn.jsdelivr.net/npm/klinecharts/dist/umd/klinecharts.min.js"></script>
<style>html,body,#chart{width:100%;height:100%;margin:0;background:#fff}#empty{padding:24px;color:#667085;font-family:sans-serif}</style>
</head><body><div id="chart"></div><script>
const rows=$data;
if(rows.length===0){document.getElementById('chart').innerHTML='<div id="empty">暂无K线数据</div>'}
else if(typeof klinecharts==='undefined'){document.getElementById('chart').innerHTML='<div id="empty">图表组件加载失败，请检查网络后重试</div>'}
else{const chart=klinecharts.init('chart');chart.applyNewData(rows);chart.createIndicator('MA');chart.createIndicator('VOL');}
</script></body></html>
""".trimIndent()
    }

    fun buildMinute(bars: List<MinuteBar>): String {
        val data = JSONArray()
        bars.forEach {
            data.put(JSONObject().apply {
                put("time", it.time)
                put("price", it.price)
                put("average", it.avgPrice)
                put("volume", it.volume)
            })
        }
        return """
<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<style>html,body{width:100%;height:100%;margin:0;background:#fff;font-family:sans-serif}canvas{width:100%;height:100%}#empty{padding:24px;color:#667085}</style>
</head><body><canvas id="chart"></canvas><div id="empty" hidden>暂无分时数据</div><script>
const rows=$data,canvas=document.getElementById('chart'),empty=document.getElementById('empty');
function draw(){
 if(rows.length<2){canvas.hidden=true;empty.hidden=false;return;}
 const dpr=window.devicePixelRatio||1,w=canvas.clientWidth,h=canvas.clientHeight;
 canvas.width=w*dpr;canvas.height=h*dpr;const c=canvas.getContext('2d');c.scale(dpr,dpr);
 const pad={l:10,r:10,t:14,b:42},plotW=w-pad.l-pad.r,plotH=h-pad.t-pad.b;
 const values=rows.flatMap(x=>[x.price,x.average]),min=Math.min(...values),max=Math.max(...values),range=(max-min)||1;
 const x=i=>pad.l+i*plotW/(rows.length-1),y=v=>pad.t+(max-v)*plotH/range;
 c.strokeStyle='#e5e7eb';c.lineWidth=1;for(let i=0;i<4;i++){const yy=pad.t+i*plotH/3;c.beginPath();c.moveTo(pad.l,yy);c.lineTo(w-pad.r,yy);c.stroke();}
 function line(key,color,width){c.strokeStyle=color;c.lineWidth=width;c.beginPath();rows.forEach((r,i)=>{const xx=x(i),yy=y(r[key]);i?c.lineTo(xx,yy):c.moveTo(xx,yy)});c.stroke();}
 line('price','#3157d5',2);line('average','#d99020',1.4);
 c.fillStyle='#667085';c.font='11px sans-serif';c.fillText(rows[0].time,pad.l,h-10);const last=rows[rows.length-1].time;c.fillText(last,w-pad.r-c.measureText(last).width,h-10);
 }
window.addEventListener('resize',draw);draw();
</script></body></html>
""".trimIndent()
    }
}
