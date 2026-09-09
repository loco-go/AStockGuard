# KLineCharts 10.0.2

- Upstream: https://github.com/klinecharts/KLineChart
- Script: https://cdn.jsdelivr.net/npm/klinecharts@10.0.2/dist/umd/klinecharts.min.js
- License source: https://cdn.jsdelivr.net/npm/klinecharts@10.0.2/LICENSE
- License: Apache-2.0; full text included in `LICENSE`, upstream copyright header retained.
- Downloaded: 2026-09-08. Script and license are unmodified.
- SHA-256 (`klinecharts.min.js`): `db288a8c5d910a907f1e74fd355bc1d7a9219022549e7dc575e3076e5c31b46a`.

Packaged inside the APK. `ChartAssetClient` serves this exact script URL from assets; the chart WebView blocks network loads and unknown resources. Updating this dependency requires reviewing its API compatibility and license, updating this checksum, and rerunning the offline chart regression test.
