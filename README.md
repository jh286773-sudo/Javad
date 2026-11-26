# JAVAD

JAVAD is an Android-focused signal application for Toobit USDT-margined perpetual contracts.

## Current repository contents

- `engine/`: Kotlin/JVM signal engine, FVG + Auto Threshold calculation, selected-timeframe intrabar analysis with Pine-style rollback, Toobit public market-data client and kline stream, shared analysis settings, monitoring recovery policy, and `ToobitSignalMonitor` lifecycle coordinator.
- `.github/workflows/engine-tests.yml`: engine test workflow for pushes and pull requests to `main`.

## Confirmed product constraints

- Market: Toobit USDT-margined perpetual contracts only; JAVAD symbols use the `.P` suffix (for example, `BTCUSDT.P`).
- Strategy: FVG + Auto Threshold only. Do not add EMA, RSI, ICT, or alternative strategies.
- One shared chart timeframe for all selected symbols: 1m, 5m, 15m, 30m, 1h, or 2h. LuxAlgo FVG is explicitly in Chart mode (`fvg_tf = ""`), so FVG OHLC, `timeframe.change`, Toobit REST history, and the matching WebSocket kline topic all use this same selected interval; there is no independent FVG timeframe. Live candle updates are evaluated intrabar and each candle is committed once on close.
- Javad calculates signals directly from Toobit public market data. TradingView, a paid TradingView plan, Pine alerts, and Webhooks are not required. No API key is used for public market data; no order-placement/trading endpoints are part of the signal engine.
- The Pine-style `bar_index` and cumulative Auto Threshold are reconstructed from the available Toobit candle dataset, independently per contract. Since Toobit and TradingView may have different candle history boundaries or OHLC values, numerical identity with a TradingView chart is not guaranteed.
- Emit every valid FVG + Auto event, including consecutive events in the same direction; keep each symbol's analysis state independent.
- Keep development on the single `main` branch.

## Important implementation status

The repository currently contains the Kotlin/JVM engine and tests, including a Toobit monitor coordinator that loads history before connecting the live stream and reloads a symbol after a detected candle gap. It is **not yet a complete installable Android app**. The Android Compose UI, Android foreground monitoring service and boot recovery integration, persistent settings wiring, and approved floating LONG/SHORT notification still need implementation and device verification.

The LuxAlgo-derived calculation carries the attribution and CC BY-NC-SA 4.0 notice in [engine/NOTICE.md](engine/NOTICE.md). The adapted code must retain that notice and is marked non-commercial under that license.

## Engine tests

Run from the `engine` directory:

```bash
gradle test --no-daemon
```
