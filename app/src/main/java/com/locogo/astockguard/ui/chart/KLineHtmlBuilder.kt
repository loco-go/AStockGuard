package com.locogo.astockguard.ui.chart

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.strategy.IntradayChartSignal
import org.json.JSONArray
import org.json.JSONObject

object KLineHtmlBuilder {
    fun build(bars: List<DailyBar>): String = buildCandles(ChartDataMapper.mapDaily(bars), period = ChartPeriod.DAY)

    fun buildCandles(
        candles: List<StockKLine>,
        signals: List<ChartSignal> = emptyList(),
        period: ChartPeriod = ChartPeriod.DAY
    ): String {
        val data = JSONArray()
        candles.forEach {
            data.put(JSONObject().apply {
                put("timestamp", it.timestamp); put("open", it.open); put("high", it.high)
                put("low", it.low); put("close", it.close); put("volume", it.volume)
            })
        }
        return """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<script src="https://cdn.jsdelivr.net/npm/klinecharts@10.0.2/dist/umd/klinecharts.min.js"></script>
<style>html,body,#chart{width:100%;height:100%;margin:0;background:#fff;font-family:sans-serif}#empty{padding:24px;color:#667085}.badge{position:absolute;z-index:2;right:8px;top:6px;padding:4px 7px;border-radius:5px;background:#ffffffdf;color:#344054;font-size:11px;pointer-events:none}</style>
</head><body><div id="period" class="badge"></div><div id="chart"></div><script>
const rows=$data,signals=${ChartSignalOverlay.toJson(signals)};
if(rows.length===0){document.getElementById('chart').innerHTML='<div id="empty">暂无K线数据</div>'}
else if(typeof klinecharts==='undefined'){document.getElementById('chart').innerHTML='<div id="empty">图表组件加载失败，请检查网络后重试</div>'}
else{
 const chart=klinecharts.init('chart',{locale:'zh-CN',timezone:'Asia/Shanghai',layout:{barSpaceLimit:{min:2,max:30},pane:{minHeight:80},yAxis:{position:'right',inside:false}}});
 const savedPosition=window.name||'realtime';let initialPositioned=false;
 chart.setStyles({
  candle:{
   // A股日/周/月K统一按本周期开盘价比较：收盘高于开盘为红，低于开盘为绿。
   // 必须显式设置 compareRule，否则不同版本可能默认按前收盘价着色。
   bar:{compareRule:'current_open',upColor:'#d92d20',downColor:'#039855',noChangeColor:'#8a8f98',upBorderColor:'#d92d20',downBorderColor:'#039855',noChangeBorderColor:'#8a8f98',upWickColor:'#d92d20',downWickColor:'#039855',noChangeWickColor:'#8a8f98'},
   priceMark:{last:{upColor:'#d92d20',downColor:'#039855',noChangeColor:'#8a8f98'}}
  },
  indicator:{
   ohlc:{compareRule:'current_open',upColor:'rgba(217,45,32,.65)',downColor:'rgba(3,152,85,.65)',noChangeColor:'#8a8f98'},
   bars:[{style:'fill',upColor:'rgba(217,45,32,.55)',downColor:'rgba(3,152,85,.55)',noChangeColor:'#8a8f98'}]
  }
 });
 chart.setSymbol({ticker:'ASTOCK',pricePrecision:2,volumePrecision:0});
 chart.setPeriod({span:1,type:'${period.toKLineChartType()}'});
 const restorePosition=()=>{
  if(initialPositioned)return;initialPositioned=true;
  const timestamp=Number(savedPosition);
  if(savedPosition!=='realtime'&&Number.isFinite(timestamp)&&rows.some(r=>r.timestamp===timestamp))chart.scrollToTimestamp(timestamp,0);
  else chart.scrollToRealTime(0);
 };
 chart.subscribeAction('onDataReady',restorePosition);
 chart.subscribeAction('onVisibleRangeChange',range=>{
  if(!range||!rows.length)return;
  const to=Math.min(rows.length-1,Math.max(0,Math.floor(range.to)));
  window.name=to>=rows.length-2?'realtime':String(rows[to].timestamp);
 });
 chart.setDataLoader({getBars:({callback})=>callback(rows)});
 chart.createIndicator({name:'MA',calcParams:[5,10,20,60],paneId:'candle_pane'},true);
 chart.createIndicator('VOL');
 chart.setBarSpace(rows.length<35?Math.max(4,Math.min(14,(document.body.clientWidth-70)/rows.length)):7);
 setTimeout(restorePosition,80);
 const names={day:'日K',week:'周K',month:'月K'},period='${period.toKLineChartType()}';
 document.getElementById('period').textContent=(names[period]||'K线')+' · '+rows.length+' 根'+(signals.length?' · '+signals[0].action+' '+signals[0].score:'');
}
</script></body></html>
""".trimIndent()
    }

    private fun ChartPeriod.toKLineChartType(): String = when (this) {
        ChartPeriod.DAY -> "day"
        ChartPeriod.WEEK -> "week"
        ChartPeriod.MONTH -> "month"
        ChartPeriod.MINUTE -> "minute"
    }

    fun buildMinute(
        bars: List<MinuteCandle>,
        signals: List<IntradayChartSignal> = emptyList()
    ): String {
        val data = JSONArray()
        bars.forEach {
            data.put(JSONObject().apply {
                put("time", it.time); put("open", it.open); put("high", it.high); put("low", it.low)
                put("close", it.close); put("average", it.average); put("volume", it.volume)
            })
        }
        val markerData = JSONArray()
        signals.forEach {
            markerData.put(JSONObject().apply {
                put("time", it.time); put("price", it.price); put("action", it.action.name)
                put("score", it.score); put("reason", it.reason); put("recommended", it.recommended)
            })
        }
        return """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<style>html,body{width:100%;height:100%;margin:0;background:#fff;font-family:sans-serif}canvas{width:100%;height:100%}#empty{padding:24px;color:#667085}</style>
</head><body><canvas id="chart"></canvas><div id="empty" hidden>暂无分时数据</div><script>
const rows=$data,signals=$markerData,canvas=document.getElementById('chart'),empty=document.getElementById('empty');
function draw(){
 if(rows.length<1){canvas.hidden=true;empty.hidden=false;return;}
 const dpr=window.devicePixelRatio||1,w=canvas.clientWidth,h=canvas.clientHeight;
 canvas.width=w*dpr;canvas.height=h*dpr;const c=canvas.getContext('2d');c.scale(dpr,dpr);
 const pad={l:12,r:12,t:24,b:22},plotW=w-pad.l-pad.r,priceH=(h-pad.t-pad.b)*.76,volumeTop=pad.t+(h-pad.t-pad.b)*.82,volumeH=(h-pad.t-pad.b)*.16;
 const prices=rows.flatMap(x=>[x.low,x.high]).concat(signals.map(s=>s.price)),rawMin=Math.min(...prices),rawMax=Math.max(...prices),pricePad=Math.max((rawMax-rawMin)*.08,rawMax*.001),min=rawMin-pricePad,max=rawMax+pricePad,range=(max-min)||1;
 const step=plotW/rows.length,body=Math.max(2,Math.min(9,step*.58)),x=i=>pad.l+(i+.5)*step,y=v=>pad.t+(max-v)*priceH/range;
 c.strokeStyle='#e5e7eb';c.lineWidth=1;for(let i=0;i<4;i++){const yy=pad.t+i*priceH/3;c.beginPath();c.moveTo(pad.l,yy);c.lineTo(w-pad.r,yy);c.stroke();}
 const maxVol=Math.max(...rows.map(r=>r.volume),1);
 rows.forEach((r,i)=>{const xx=x(i),color=r.close>r.open?'#d92d20':(r.close<r.open?'#039855':'#8a8f98');c.strokeStyle=color;c.fillStyle=color;c.beginPath();c.moveTo(xx,y(r.high));c.lineTo(xx,y(r.low));c.stroke();const top=Math.min(y(r.open),y(r.close)),height=Math.max(1,Math.abs(y(r.open)-y(r.close)));c.fillRect(xx-body/2,top,body,height);const vh=r.volume/maxVol*volumeH;c.globalAlpha=.35;c.fillRect(xx-body/2,volumeTop+volumeH-vh,body,vh);c.globalAlpha=1;});
 c.strokeStyle='#d99020';c.lineWidth=1.4;c.beginPath();rows.forEach((r,i)=>{i?c.lineTo(x(i),y(r.average)):c.moveTo(x(i),y(r.average))});c.stroke();
 signals.forEach(s=>{const i=rows.findIndex(r=>r.time===s.time);if(i<0)return;const buy=s.action==='BUY',rawX=x(i),rawY=y(s.price)+(buy?14:-14);c.fillStyle=buy?'#d92d20':'#039855';c.font='bold 11px sans-serif';c.textAlign='center';const label=(s.recommended?(buy?'▲ 推荐买':'▼ 推荐卖'):(buy?'▲ 买':'▼ 卖'))+' '+s.price.toFixed(2),textW=c.measureText(label).width,xx=Math.max(pad.l+textW/2,Math.min(w-pad.r-textW/2,rawX)),yy=Math.max(24,Math.min(volumeTop-4,rawY));c.fillText(label,xx,yy);});
 c.textAlign='left';c.fillStyle='#667085';c.font='11px sans-serif';c.fillText('5分钟K  ·  均价线',pad.l,14);c.fillText(rows[0].time,pad.l,h-6);const last=rows[rows.length-1].time;c.fillText(last,w-pad.r-c.measureText(last).width,h-6);
}
window.addEventListener('resize',draw);draw();
</script></body></html>
""".trimIndent()
    }
}
