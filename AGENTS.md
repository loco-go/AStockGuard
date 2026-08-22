# AStockGuard Engineering Rules

## Baseline
- `master` is the validated product baseline until the repository default branch is changed intentionally.
- Never develop directly on `master`; use `feature/*` or `fix/*` branches and merge through PRs.

## Architecture
- UI must not call Tencent/Eastmoney/AI network clients directly.
- UI observes `ViewModel` state; one-shot navigation/work requests use effects/events.
- `Repository` owns remote/local data decisions.
- Room is the local source of truth for persisted market/strategy data.
- Data source freshness must be explicit. Stale/cache data must not emit actionable real-time BUY/SELL notifications.
- `RiskEngine` and `R2Engine` are deterministic domain logic. Refactors must preserve existing outputs unless a strategy-version change is intentional.

## AI
- ChatGPT Web DOM automation is an optional capability layer, not a dependency of market monitoring.
- Normal Web AI runs invisibly; visible WebView is reserved for login/verification/DOM recovery.
- AI output must include the structured `<ASTOCK_STRATEGY>` JSON contract.
- Persist both raw AI text and parsed strategy data.
- Local rule actions and AI actions remain separate; future UI must surface conflicts rather than silently overwrite one with the other.

## Persistence
- All Room schema changes require a version bump and a migration.
- Keep exported Room schemas under `app/schemas/`.
- Add migration tests before production data migrations.

## Git/CI
- Branch naming: `feature/<topic>`, `fix/<topic>`, `refactor/<topic>`.
- PR checks should include `test`, `lint`, and `assembleDebug`.
- Do not commit credentials, cookies, ChatGPT session data, or API keys.
