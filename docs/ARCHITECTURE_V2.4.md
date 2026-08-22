# V2.4 Architecture Foundation

```text
MainActivity
  -> MainViewModel (StateFlow / Effect)
      -> MarketRepository
          -> TencentMarketClient / TencentHistoryClient
          -> Room CacheDao
      -> AiClient (API providers)
      -> AI result persistence

MainActivity
  -> HiddenChatGptSession (1x1 attached WebView)
      -> structured answer -> MainViewModel
      -> login/DOM/network error -> visible ChatGptWebActivity recovery

MarketMonitorService
  -> shared AppContainer.MarketRepository
      -> MonitorBus
      -> notifications (disabled for stale cache data)
```

## V2.4 invariants
1. Existing Tencent parsing, RiskEngine and R2Engine behavior stays unchanged for live data.
2. Network success writes cache; network failure may read cache.
3. Cached market data is visibly marked stale and cannot generate actionable service alerts.
4. MainActivity does not directly instantiate MarketRepository/AiClient.
5. AI web navigation is not shown during the normal path.

## Next migration step (V2.5)
- Replace text holdings output with RecyclerView/Compose strategy table.
- Add `StockStrategyUiModel` that combines Local Action, AI Action, fund flow and data freshness.
- Introduce first chart cards and design system without changing the market data protocol.
