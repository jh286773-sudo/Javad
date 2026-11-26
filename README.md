# JAVAD

JAVAD is an Android-focused signal application for Toobit USDT-margined perpetual contracts.

## Current repository contents

- `engine/`: Kotlin/JVM signal engine, FVG + Auto Threshold calculation, selected-timeframe closed-candle analysis pipeline, Toobit public market-data client and kline stream, shared analysis settings, and monitoring recovery policy.
- `.github/workflows/engine-tests.yml`: engine test workflow for pushes and pull requests to `main`.

## Confirmed product constraints

- Market: Toobit USDT-margined perpetual contracts only; JAVAD symbols use the `.P` suffix (for example, `BTCUSDT.P`).
- Strategy: FVG + Auto Threshold only. Do not add EMA, RSI, ICT, or alternative strategies.
- One shared timeframe for all selected symbols: 1m, 5m, 15m, 30m, 1h, or 2h. The selected interval is used for both Toobit REST history and the matching WebSocket kline topic; only closed candles are passed to FVG + Auto analysis.
- No API key is used for public market data; no order-placement/trading endpoints are part of the signal engine.
- Emit every valid FVG + Auto event, including consecutive events in the same direction; keep each symbol's analysis state independent.
- Keep development on the single `main` branch.

## Important implementation status

The repository currently contains the Kotlin/JVM engine and tests. It is **not yet a complete installable Android app**. The Android Compose UI, Android foreground monitoring service and boot recovery integration, persistent settings, complete reconnect/backfill orchestration, and approved floating LONG/SHORT notification still need implementation and device testing.

The LuxAlgo-derived calculation carries the attribution and CC BY-NC-SA 4.0 notice in [engine/NOTICE.md](engine/NOTICE.md). The adapted code must retain that notice and is marked non-commercial under that license.

## Engine tests

Run from the `engine` directory:

```bash
gradle test --no-daemon
```
