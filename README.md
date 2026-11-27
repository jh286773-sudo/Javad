# JAVAD

JAVAD is an Android-focused signal application for Toobit USDT-margined perpetual contracts.

## Current repository contents

- `engine/`: Kotlin/JVM signal engine, FVG + Auto Threshold calculation, selected-timeframe intrabar analysis with Pine-style rollback, Toobit public market-data client and kline stream, shared analysis settings, monitoring recovery policy, `ToobitSignalMonitor` lifecycle coordinator, and `JavadSignalRuntime` public entry point for application integration.
- `.github/workflows/engine-tests.yml`: engine test workflow for pushes and pull requests to `main`.

## Confirmed product constraints

- Market: Toobit USDT-margined perpetual contracts only; JAVAD symbols use the `.P` suffix (for example, `BTCUSDT.P`).
- Candle source: Toobit standard OHLC is converted to Heikin Ashi before strategy calculations. Both FVG and Auto Threshold use the Heikin Ashi open/high/low/close values; raw exchange candles are not used as strategy inputs.
- Strategy: exactly two components, FVG + Auto Threshold only. FVG's price-gap condition references three candles; those are candle inputs to the FVG formula, not three separate indicators. Do not add EMA, RSI, ICT, or alternative strategies.
- One shared chart timeframe for all selected symbols: 1m, 5m, 15m, 30m, 1h, or 2h; the initial default is 5m for the current BTCUSDT.P comparison. LuxAlgo FVG is explicitly in Chart mode (`fvg_tf = ""`), so FVG OHLC, `timeframe.change`, Toobit REST history, and the matching WebSocket kline topic all use this same selected interval; there is no independent FVG timeframe. Live candle updates are evaluated intrabar and each candle is committed once on close.
- Javad calculates signals directly from Toobit public market data. TradingView, a paid TradingView plan, Pine alerts, and Webhooks are not required. No API key is used for public market data; no order-placement/trading endpoints are part of the signal engine.
- For Auto Threshold, JAVAD requests the earliest candle available from Toobit for each selected USDT perpetual and chart timeframe, then downloads the completed history in chronological pages (Toobit allows up to 1,000 klines per request). There is no fixed 5,000-candle cap. The oldest returned candle becomes Pine-style `bar_index = 0`; the cumulative absolute-delta sum starts there and continues through the latest completed candle, independently per contract. Exact numerical identity with TradingView is still not guaranteed because the two feeds may differ in first available candle, OHLC values, timezone alignment, or historical revisions.
- Signal delivery is limited to one notification per candle, but qualifying signals on later candles may repeat in the same direction. Only duplicate notifications for the same candle are suppressed. Each symbol keeps an independent FVG/Auto state.
- Keep development on the single `main` branch.

## FVG + Auto Threshold audit

The calculation and timeframe mapping are documented in [`FVG_TIMEFRAME_AUDIT.md`](FVG_TIMEFRAME_AUDIT.md). The engine first converts Toobit OHLC to Heikin Ashi (first HA open = (open + close) / 2; subsequent HA open = (previous HA open + previous HA close) / 2; HA close = (open + high + low + close) / 4; HA wicks include the HA open/close). It then ports the LuxAlgo Chart-mode formulas using those HA candles: previous-candle percentage delta, cumulative absolute-delta Auto Threshold divided by the Pine chart `bar_index` and multiplied by 2, and the original bullish/bearish three-candle gap conditions. The first candle in each loaded chart dataset is index 0; the first Auto Threshold is undefined at index 0, as in Pine. Each symbol maintains its own cumulative state.

The TradingView 5-minute comparison anchor supplied for this project is 2026-11-09 00:00 UTC. A live Toobit API test on 2026-11-27 returned 3,499 completed BTCUSDT.P and ETHUSDT.P candles from 2026-11-15 04:55 UTC through 2026-11-27 08:25 UTC. That Toobit start is 1,787 five-minute bars later than the TradingView comparison anchor, so Auto Threshold values and signals can differ even with identical formulas. The candle beginning 2026-11-12 13:05 UTC is 1,021 five-minute intervals after the TradingView anchor and maps to `bar_index = 1021` on that TradingView dataset. These are timestamp/index checks, not proof that Toobit and TradingView OHLC values are identical.

## Important implementation status

The repository currently contains the Kotlin/JVM engine and tests, including an integrated `JavadSignalRuntime` entry point. It loads Toobit history before connecting the live stream and reloads a symbol after a detected candle gap. It is **not yet a complete installable Android app**. The Android application layer must instantiate `JavadSignalRuntime`, call `start()` on a background executor, route `onSignal` to the approved floating LONG/SHORT notification, persist settings, and connect the foreground monitoring service and boot/network recovery policy. The Android Compose UI, Android adapters, and device verification still need implementation.

The LuxAlgo-derived calculation carries the attribution and CC BY-NC-SA 4.0 notice in [engine/NOTICE.md](engine/NOTICE.md). The adapted code must retain that notice and is marked non-commercial under that license.

## Engine tests

Run from the `engine` directory:

```bash
gradle test --no-daemon
```
