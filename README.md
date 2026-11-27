# JAVAD

JAVAD is an Android-focused signal application for Toobit USDT-margined perpetual contracts.

## Current repository contents

- `engine/`: Kotlin/JVM signal engine, FVG + Auto Threshold calculation, selected-timeframe intrabar analysis with Pine-style rollback, Toobit public market-data client and kline stream, shared analysis settings, monitoring recovery policy, `ToobitSignalMonitor` lifecycle coordinator, and `JavadSignalRuntime` public entry point for application integration.
- `.github/workflows/engine-tests.yml`: engine test workflow for pushes and pull requests to `main`.

## Confirmed product constraints

- Market: Toobit USDT-margined perpetual contracts only; JAVAD symbols use the `.P` suffix (for example, `BTCUSDT.P`).
- Strategy: FVG + Auto Threshold only. Do not add EMA, RSI, ICT, or alternative strategies.
- One shared chart timeframe for all selected symbols: 1m, 5m, 15m, 30m, 1h, or 2h; the initial default is 5m for the current BTCUSDT.P comparison. LuxAlgo FVG is explicitly in Chart mode (`fvg_tf = ""`), so FVG OHLC, `timeframe.change`, Toobit REST history, and the matching WebSocket kline topic all use this same selected interval; there is no independent FVG timeframe. Live candle updates are evaluated intrabar and each candle is committed once on close.
- Javad calculates signals directly from Toobit public market data. TradingView, a paid TradingView plan, Pine alerts, and Webhooks are not required. No API key is used for public market data; no order-placement/trading endpoints are part of the signal engine.
- For the TradingView Basic-plan comparison, all supported timeframes use a 5,000-bar lookback aligned to the documented calendar boundary (1–14m: week; 15–29m: month; 30–1439m: year). For the active 5-minute comparison, the 5,000-bar threshold date is aligned to the beginning of its UTC week. The first returned completed candle becomes Pine-style `bar_index = 0`; the Auto Threshold cumulative sum starts there and continues through the latest completed candle, independently per contract. The previously discussed 2026-11-09 00:00 UTC candle is a historical comparison point, not a hard-coded start. Exact numerical identity is still not guaranteed because Toobit and TradingView may differ in symbol feed, exchange timezone, first available candle, and OHLC revisions.
- Signal delivery is limited to one notification per candle. After a LONG signal, further LONG signals are suppressed until a qualifying SHORT signal is confirmed; after a SHORT signal, further SHORT signals are suppressed until a qualifying LONG signal is confirmed. Each symbol keeps an independent FVG/Auto state and direction memory.
- Keep development on the single `main` branch.

## FVG + Auto Threshold audit

The calculation and timeframe mapping are documented in [`FVG_TIMEFRAME_AUDIT.md`](FVG_TIMEFRAME_AUDIT.md). The engine ports the LuxAlgo Chart-mode formulas: previous-candle percentage delta, cumulative absolute-delta Auto Threshold divided by the Pine chart `bar_index` and multiplied by 2, and the original bullish/bearish three-candle gap conditions. The first candle in each loaded chart dataset is index 0; the first Auto Threshold is undefined at index 0, as in Pine. Each symbol maintains its own cumulative state.

The 5-minute comparison anchor supplied for this project is 2026-11-09 00:00 UTC as the first chart candle. The candle beginning 2026-11-12 13:05 UTC is 1,021 five-minute intervals later and therefore maps to `bar_index = 1021` if both datasets begin at that same first candle. This is a timestamp/index check, not proof that Toobit and TradingView OHLC values are identical.

## Important implementation status

The repository currently contains the Kotlin/JVM engine and tests, including an integrated `JavadSignalRuntime` entry point. It loads Toobit history before connecting the live stream and reloads a symbol after a detected candle gap. It is **not yet a complete installable Android app**. The Android application layer must instantiate `JavadSignalRuntime`, call `start()` on a background executor, route `onSignal` to the approved floating LONG/SHORT notification, persist settings, and connect the foreground monitoring service and boot/network recovery policy. The Android Compose UI, Android adapters, and device verification still need implementation.

The LuxAlgo-derived calculation carries the attribution and CC BY-NC-SA 4.0 notice in [engine/NOTICE.md](engine/NOTICE.md). The adapted code must retain that notice and is marked non-commercial under that license.

## Engine tests

Run from the `engine` directory:

```bash
gradle test --no-daemon
```
