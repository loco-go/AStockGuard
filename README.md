# AStockGuardDemo

纯 Android 单机版 A 股实时行情 / 仓位闸门 / AI 辅助 Demo。

> 当前版本只做“读取行情 + 本地信号 + 本地通知 + AI 分析”，**不自动下单**。

## 已实现

- Kotlin + XML + ViewBinding
- iFinD HTTP API：
  - `POST /api/v1/get_access_token`
  - `POST /api/v1/real_time_quotation`
  - `POST /api/v1/cmd_history_quotation`（用于上一交易日收盘价）
- 3 秒轮询的 `ForegroundService`
- Android 15 `dataSync` FGS `onTimeout()` 处理
- 手工输入 iFinD `refresh_token`
- 手工输入 OpenAI / OpenAI-compatible Responses API Key
- 主接口 + 备用接口；仅在网络错误、408、429、5xx 时切换备用，不对 401/403 做“绕过”
- Android Keystore + AES/GCM 本地加密保存：
  - iFinD refresh token
  - 主 API Key
  - 备用 API Key
- 本地风险模型：E0/E1/E2、M1/M2/M3、建议总仓位上限
- 持仓角色：`CORE / ATTACK / TRADE / LONG`
- 风险信号变化时本地 Notification
- AI 只接收当前行情快照和本地信号，不让模型编造实时行情

## 为什么支持自定义 AI Base URL

Demo 默认使用：

```text
https://api.openai.com/v1/responses
```

设置页可以把 `Base URL` 改成**你自己有权使用的 OpenAI-compatible Responses provider**。App 会在 Base URL 后拼接 `/responses`。

例如：

```text
Base URL = https://api.openai.com/v1
最终请求 = https://api.openai.com/v1/responses
```

如果你之前 Codex 配置里使用了自定义 `model_provider.base_url`，只要该服务实现 OpenAI-compatible Responses 协议，也可以直接填进来。

本项目**不使用**未公开的 ChatGPT/Codex 后台接口，也不内置任何规避服务权限/封禁的逻辑。

## API Key 安全说明

个人 Demo 阶段采用“用户手动输入 + Android Keystore AES/GCM 加密本地保存”，比把 Key 写死在 APK 中安全很多。

但客户端永远不能达到服务器级别的密钥隔离：root、hook、内存抓取或设备失陷仍可能泄露 Key。正式多人版本建议把 AI Key 迁移到后端代理。

## iFinD 配置

官方 HTTP API 使用：

1. `refresh_token` 获取 `access_token`
2. `access_token` 调取实时行情

在设置页手动输入 refresh token。默认自选：

```text
000636.SZ,000938.SZ,600667.SH,002579.SZ
```

可自行修改。

持仓格式：

```text
代码,名称,股数,成本,角色
000636.SZ,风华高科,500,45.679,CORE
000938.SZ,紫光股份,600,48.162,CORE
600667.SH,太极实业,800,23.492,ATTACK
002579.SZ,中京电子,600,15.317,LONG
```

## 本地仓位模型（Demo 规则）

这只是可运行的第一版规则，不代表最终策略：

```text
E2:
  观察池平均跌幅 <= -4%
  OR <-7% 标的占比 >= 40%
  -> 仓位上限 50%

E1:
  平均跌幅 <= -2%
  OR 下跌标的占比 >= 70%
  -> 仓位上限 65%

M3:
  平均涨幅 >= +2%
  AND 下跌占比 <= 35%
  -> 仓位上限 85%
```

下一版应该加入：

- 全市场上涨家数 / 跌停家数
- SOX / KOSPI / 日经 / 美债 / 油价事件风险
- 板块强弱
- VWAP
- 分时第一低点 / 第二低点
- MA5 / MA10 / MA20
- R2（Recovery + Second Pullback）扫描
- Level-2 大单 / 逐笔 / 盘口不平衡

## 编译

推荐：

- Android Studio Narwhal 及以上
- JDK 17+
- Android SDK 35

本仓库使用 AGP 8.7.3 + Gradle 8.9。

为了让压缩包不依赖二进制 `gradle-wrapper.jar`，这里的 `gradlew/gradlew.bat` 是一个很小的 bootstrap 脚本：首次运行会自动下载官方 Gradle 8.9 distribution。

Windows：

```bat
gradlew.bat assembleDebug
```

macOS/Linux：

```bash
./gradlew assembleDebug
```

也可以直接用 Android Studio 打开项目。

## 注意

- iFinD 指标权限取决于你的账号权限。
- 当前实时接口只请求官方示例明确使用的 `open,high,low,latest`，涨跌幅由历史上一交易日收盘价本地计算。
- 纯 Android Demo 没有服务器，因此 AI 调用依赖手机当前网络。
- 本项目用于研究和个人辅助，不构成投资建议，也不保证任何收益。
