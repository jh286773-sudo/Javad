# JAVAD

JAVAD is an Android-focused signal application for Toobit USDT-margined perpetual contracts.

## Current repository contents

- `engine/`: Kotlin/JVM signal engine, LuxAlgo-derived FVG + Auto Threshold calculation, Heikin Ashi conversion, Toobit REST/WebSocket market-data clients, adaptive history loader, and `JavadSignalRuntime` entry point.
- `.github/workflows/engine-tests.yml`: engine test workflow for pushes and pull requests to `main`.

## Confirmed product constraints

- Market: Toobit USDT-margined perpetual contracts only; JAVAD symbols use the `.P` suffix (for example, `BTCUSDT.P`).
- Candle source: Toobit standard OHLC is converted to Heikin Ashi before strategy calculations. Both FVG and Auto Threshold use Heikin Ashi open/high/low/close. The committed HA series is chronological and oldest-first; live previews do not advance the recursive HA state until close.
- Strategy: exactly FVG + Auto Threshold, following the LuxAlgo Chart-mode formulas. No EMA, RSI, ICT, or alternative strategy.
- One shared chart timeframe for all selected symbols: 1m, 5m, 15m, 30m, 1h, or 2h. There is no separate FVG timeframe.
- Each symbol has independent FVG/Auto state, history, bar index, and cumulative absolute-delta calculation.
- Signals follow the qualifying LuxAlgo FVG + Auto conditions. No extra same-candle or same-direction suppression is applied.
- Development stays on the single `main` branch.

## Adaptive history loading — current production rule

The history window is sized from the actual latest fully closed candle returned by Toobit. It is not based on the hour the user starts the app, a hard-coded start date, or a universal 5,000-candle cap.

The user-confirmed target counts for the six supported timeframes are:

| Timeframe | Target candle count |
|---|---:|
| 1m | 7,200 |
| 5m | 5,472 |
| 15m | 5,568 |
| 30m | 33,408 |
| 1h | 7,944 |
| 2h | 8,352 |

For each selected symbol and timeframe, JAVAD performs this sequence on startup/restart and history recovery:

1. Read Toobit's server time and determine the open-time boundary of the current/forming candle.
2. Request the latest fully closed candle from Toobit and validate its actual timestamp against the selected interval.
3. Find the next UTC midnight strictly after the exchange time.
4. Count the candle slots from the current candle's open time up to, but excluding, that midnight. This count includes the current forming slot and future slots.
5. Subtract that remaining count from the configured target count for the selected timeframe. The result is the exact number of completed historical candles to load, including the latest closed candle.
6. Calculate the first candle timestamp from the latest closed candle and that completed-candle count. Request only the older candles from Toobit, then append the already fetched latest closed candle.
7. Validate exact count, chronological order, interval alignment, uniqueness, and gap-free continuity. If any required candle is unavailable, reject the history and do not start analysis with a shifted `bar_index`.
8. Load the verified candles oldest-first. The first candle is `bar_index = 0` and the origin of Heikin Ashi recursion and Auto Threshold accumulation. Only after history is ready does the live stream begin.

The REST history end is exclusive at the current candle's open time. Therefore, the candle currently forming—including a candle that opens at 00:00 UTC—is not counted as a completed historical candle. The WebSocket may evaluate it as a live preview; it is committed to history only after it closes.

The calculated first-candle boundary moves automatically as time advances. At each UTC midnight the remaining-slot count resets for the new day, so the left edge advances by one day. If the app was offline, startup/recovery recomputes the window from Toobit's current latest closed candle and backfills the required history before resuming live analysis.

The target counts above are timeframe-specific history sizes, not a shared fixed cap. All candles in the calculated history are passed chronologically through Heikin Ashi, FVG, and Auto Threshold. Page boundaries never reset `bar_index` or cumulative Auto state.

## FVG + Auto Threshold calculation

The calculation and timeframe mapping are documented in [`FVG_TIMEFRAME_AUDIT.md`](FVG_TIMEFRAME_AUDIT.md). JAVAD converts raw Toobit OHLC to Heikin Ashi and ports the LuxAlgo Chart-mode formulas. Auto Threshold uses the cumulative absolute previous-candle percentage delta divided by Pine-style `bar_index` and multiplied by 2. The first dataset candle has index zero and an undefined threshold, matching Pine behavior.

The adapted calculation retains attribution and the CC BY-NC-SA 4.0 notice in `engine/NOTICE.md`. The adaptation is non-commercial under that license.

## Important implementation status

The repository contains the Kotlin/JVM engine and tests, including `JavadSignalRuntime`. It loads Toobit history before connecting the live stream and reloads history after a detected gap. It is **not yet a complete installable Android app**. The Android application layer still needs to instantiate the runtime, route `onSignal` to the approved notification, persist settings, connect the foreground monitoring service and recovery policy, and complete Compose UI/device verification.

## Engine tests

Run from the `engine` directory:

```bash
gradle test --no-daemon
```
