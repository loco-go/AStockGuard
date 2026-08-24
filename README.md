# AStockGuard

Android 单机优先的 A 股交易研究、持仓管理、盘中执行辅助与复盘驾驶舱。

> AStockGuard 是决策辅助软件：读取行情、计算本地规则、同步成交、生成计划、提醒与复盘；**不会通过辅助功能自动提交真实账户委托，也不保证投资收益。**

## 下载 APK

- [最新开发版 APK（latest-dev）](https://github.com/loco-go/AStockGuard/releases/tag/latest-dev) — 任意 `feature/**` 最新成功构建，Prerelease。
- [最新 master APK（latest-master）](https://github.com/loco-go/AStockGuard/releases/tag/latest-master) — master 最新成功构建。
- [全部 Releases](https://github.com/loco-go/AStockGuard/releases)

当前滚动 Release 使用开发/验证签名。需要稳定覆盖安装的 production APK 时，应为 GitHub Actions 配置固定 release keystore secrets，不能在 CI 中临时生成签名密钥。

## 当前能力

### 交易驾驶舱

首页按任务拆成四个工作区：

- **决策**：市场风险、当前仓位、Local/AI 动作、T计划、R2、信号、新闻与 AI。
- **图表**：ECharts 分时/日K、实际买卖点、计划买卖区、失效位、Level2。
- **资金**：个股资金流、行业/概念资金排行、组合净值。
- **复盘**：实际交易统计、模拟盘、Replay、AI复盘。

### 行情与缓存

- 腾讯实时行情、历史日线、分钟数据。
- VWAP / MA / R2 等本地因子。
- Room Offline-first 缓存。
- 网络失败可以展示缓存；**STALE 行情禁止推进实时 BUY/SELL/T 动作。**

### ECharts 交易图

- Apache ECharts 6.1.0。
- 分时：价格、均价、成交量、缩放/指针。
- 日K：Candlestick、成交量。
- 实际 `TradeRecord` 映射为 BUY/SELL 标记。
- T计划显示买入区、卖出区、失效位。
- 点击图表价格可设为“计划买点锚点”，随后重新计算卖出区；点击不会自动下单。

### T 交易执行辅助

`TTradePlanner` 使用近 60 分钟振幅、VWAP、可选资金流、市场阶段和现有底仓计算：

- `BUY_ZONE`
- `SELL_ZONE`
- `WAIT / WAIT_RECLAIM`
- `INVALIDATED`
- `NO_T`
- `BLOCKED_STALE`

基本约束：

- 无至少 100 股现有底仓时不生成盘中 T 计划。
- 振幅不足时主动输出 `NO_T`，不为了交易而交易。
- 建议 T 仓默认不超过底仓约 1/3，防守阶段进一步压缩。
- 盯盘服务仅在交易时段、实时行情下按状态变化通知，不连续刷屏。
- A 股新买股票受 T+1 约束；后续还要结合券商“可用数量”把可卖股数约束做完整。

### 同花顺成交同步

两条只读路径统一写入本地 `TradeRecord`：

1. **Accessibility 盘中补充**
   - 用户自己打开同花顺成交/交割页面。
   - 仅读取可访问的代码、方向、价格、数量、时间。
   - 不调用 `performAction()`，不自动点击、不下单、不保存整页原始文本。

2. **交割单文件导入**
   - 当前支持 CSV / TSV / TXT。
   - 支持常见中英文成交表头。
   - 作为更可靠的盘后对账路径。

同步后的真实成交会自动成为 ECharts 买卖点和复盘数据。

### 资金流

- 东方财富个股分钟/日级资金流。
- 主力、超大单、大单、中单、小单。
- 1/3/5/10 日聚合。
- 行业 / 概念板块资金排行。
- 资金分类仅作为数据商订单规模口径，不等同于真实机构账户身份。

### Level2

- Provider-neutral 十档盘口模型。
- `MOCK` / `HTTP_JSON` Provider。
- REAL / MOCK / STALE 明确标记。
- Mock 数据不会进入 AI 作为真实 Level2 证据。
- Level2 Token 使用 Android Keystore 加密。

### AI

- Hidden ChatGPT WebView：正常分析隐藏运行，只有登录/验证码/DOM异常时才打开可见页面。
- API Provider / Backup Provider。
- 结构化 `<ASTOCK_STRATEGY>` JSON：市场动作、置信度、目标仓位、股票动作、触发条件、失效条件。
- Local Action 与 AI Action 分开显示，冲突显式提示。

### 信号生命周期与复盘

- R2 Scanner。
- `IDLE → WATCH → READY → TRIGGERED → CONFIRMED / INVALIDATED`。
- 状态持久化、通知去重/冷却。
- 信号发生价格与后续 directional edge 统计。
- 实际交易 FIFO 已实现盈亏与胜率统计。

### 模拟盘 / Replay

- 独立模拟账户、现金、持仓、订单、净值。
- 分钟数据逐帧 Replay。
- VWAP 策略研究回放。
- 收益率、最大回撤、胜率、Profit Factor。
- 模拟盘与真实持仓完全隔离。

### 新闻风险

- HTTPS RSS/Atom。
- 新闻本地缓存与刷新节流。
- E0/E1/E2 新闻风险覆盖层与证据标题。
- 新闻风险不会单独变成 BUY/SELL。

### 本地安全与备份

- Room 数据库与显式 migrations。
- Android Keystore + AES-GCM 保存敏感凭据。
- JSON 备份/恢复持仓配置、信号、交易、模拟盘等非敏感数据。
- API Key、Cookie、Session Token、Level2 Token 不进入备份。

## 自动构建与 Release

PR / master Android CI：

```text
unit tests → lint → assembleDebug → artifact
```

Push 到 `feature/**` 或 `master`：

```text
unit tests → lint → assembleDebug → GitHub rolling Release APK
```

- `feature/**` → `latest-dev`
- `master` → `latest-master`

## 编译

推荐：

- JDK 17+
- Android SDK 35
- AGP 8.7.3
- Gradle 8.9

Windows：

```bat
gradlew.bat assembleDebug
```

macOS/Linux：

```bash
chmod +x gradlew
./gradlew assembleDebug
```

## 当前重点路线

后端/多设备同步暂时冻结。Android APP 完整之前，开发优先级为：

1. 交易决策中心与盘前/盘中计划。
2. 全市场机会扫描与板块/个股相对强度。
3. 资金流加速度与价格/资金背离。
4. 动态仓位、组合相关性、利润保护。
5. 做 T 执行辅助、净交易成本与实际 T 贡献统计。
6. 回测 2.0 / Walk Forward / T+1 / 滑点 / 涨跌停成交约束。
7. Signal Fusion、策略排行榜与动态权重。
8. 个人交易行为模型和收益归因。
9. UI、性能与数据源容灾。
10. Android 完整正式版后再考虑后端。

详见 `V3.4_CHANGELOG.md`。

## 风险说明

AStockGuard 的目标是减少冲动交易、让信号可验证、提高资金使用效率并控制回撤。历史回测、资金流、Level2、AI 或 T 交易计划都可能失效；任何策略都应以实际成交成本、样本外表现和长期期望收益验证，不能把单次提示当作收益保证。
