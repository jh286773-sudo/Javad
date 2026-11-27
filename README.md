# JAVAD

JAVAD is an Android-focused signal application for Toobit USDT-margined perpetual contracts.

## Current repository contents

- `engine/`: Kotlin/JVM signal engine, FVG + Auto Threshold calculation, selected-timeframe intrabar analysis with Pine-style rollback, Toobit public market-data client and kline stream, shared analysis settings, monitoring recovery policy, `ToobitSignalMonitor` lifecycle coordinator, and `JavadSignalRuntime` public entry point for application integration.
- `.github/workflows/engine-tests.yml`: engine test workflow for pushes and pull requests to `main`.

## Confirmed product constraints

- Market: Toobit USDT-margined perpetual contracts only; JAVAD symbols use the `.P` suffix (for example, `BTCUSDT.P`).
- Candle source: Toobit standard OHLC is converted to Heikin Ashi before strategy calculations. Both FVG and Auto Threshold use the Heikin Ashi open/high/low/close values; raw exchange candles are not used as strategy inputs. The complete committed Heikin Ashi series is constructed chronologically from the first available dataset candle through the latest closed candle and retained oldest-first. Each next HA open uses the previous committed HA open/close. A live preview derives from the last committed HA candle and is not appended until close. Signal candle payloads expose the HA candle used by the strategy. Raw Toobit OHLCV snapshots are retained separately only for detecting source-feed revisions during REST reconciliation; they are never passed to FVG or Auto calculations.
- Strategy: exactly two components, FVG + Auto Threshold only. FVG's price-gap condition references three candles; those are candle inputs to the FVG formula, not three separate indicators. Do not add EMA, RSI, ICT, or alternative strategies.
- One shared chart timeframe for all selected symbols: 1m, 5m, 15m, 30m, 1h, or 2h; the initial default is 5m for the current BTCUSDT.P comparison. 3h and 4h are not supported or selectable. LuxAlgo FVG is explicitly in Chart mode (`fvg_tf = ""`), so FVG OHLC, `timeframe.change`, Toobit REST history, and the matching WebSocket kline topic all use this same selected interval; there is no independent FVG timeframe. Live candle updates are evaluated intrabar and each candle is committed once on close.
- Javad calculates signals directly from Toobit public market data. TradingView, a paid TradingView plan, Pine alerts, and Webhooks are not required. No API key is used for public market data; no order-placement/trading endpoints are part of the signal engine.
- Required history boundary: the left edge advances by exactly 24 hours at each 00:00 UTC, removing 1,440 (1m), 288 (5m), 96 (15m), 48 (30m), 24 (1h), or 12 (2h) candles. Independently, the right edge follows the latest/current market candle and advances intraday whenever a new timeframe candle begins; it does not wait for midnight. REST seeds completed candles up to the current candle's open-time boundary, while the WebSocket supplies the forming candle as an intrabar preview. The live-window candle count includes that current forming candle. At midnight, the left edge shifts and the full shifted dataset is rebuilt from its first candle (`bar_index = 0`).
- Signal delivery follows the LuxAlgo FVG + Auto conditions without extra per-candle or per-direction suppression. Every qualifying live evaluation can emit, including repeated evaluations of the same candle and consecutive signals in the same direction. Each symbol keeps an independent FVG/Auto state.
- Keep development on the single `main` branch.



### Automatic history-window refresh

For each selected contract and chart timeframe independently, every start requests the exact configured rolling window from Toobit using paginated REST calls. The first returned candle must match the required window anchor and every expected interval must be present. If Toobit does not expose the required first candle, JAVAD rejects the incomplete window and does not start analysis with a shifted `bar_index` or altered Auto Threshold. This is intentional: silently accepting a shorter history would change the strategy. At each UTC midnight the loader requests the newly shifted window and rebuilds Heikin Ashi, `bar_index`, cumulative Auto Threshold, and FVG state before continuing. The latest completed REST candle seeds the WebSocket cursor. The right boundary is the current candle's open time and moves with market time throughout the day; it is not held until midnight. WebSocket updates deliver the current forming candle as a preview, while REST reconciliation checks closed-candle continuity and revisions. The midnight task changes only the left boundary. The observed 5m anchor (2026-11-09 00:00 UTC) is earlier than the earliest 5m candle returned in the last recorded Toobit API test (2026-11-15 04:55 UTC); therefore the exact 5m rolling window was not yet retrievable in that test, and live analysis must remain blocked until Toobit exposes the required history. The bounded recent-history integration test validates API pagination and formulas, not full-window TradingView parity.

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
