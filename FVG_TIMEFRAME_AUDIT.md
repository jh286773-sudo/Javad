# JAVAD — FVG + Auto Threshold and adaptive history audit

## Scope and current rule

This document covers only the LuxAlgo Smart Money Concepts Fair Value Gap (FVG) calculation, Auto Threshold, Heikin Ashi inputs, selected chart timeframe, history indexing, and live-candle behavior.

**Current production history rule:** Section "Adaptive Toobit history window" below supersedes any older fixed-anchor, full-available-history, or 24-hour-only history description. JAVAD sizes history from the actual latest fully closed Toobit candle, subtracts the remaining candle slots to the next UTC midnight from the user-confirmed target count for that timeframe, and requests exactly the remaining older candles.

The implementation reference is the publicly available Smart Money Concepts [LuxAlgo] Pine source mirror at:
- https://github.com/toikade/LuxAlgoScriptsAndOthers/blob/main/SmartmoneyConcepts

This is a public mirror, not a claim that the file is the latest official LuxAlgo release. The adapted engine retains the source attribution and CC BY-NC-SA 4.0 notice in `engine/NOTICE.md`. This adaptation is marked non-commercial.

## 1. Heikin Ashi candle source

JAVAD receives standard Toobit OHLC candles and converts each candle to Heikin Ashi before supplying any price or body input to FVG + Auto Threshold. The conversion is recursive and chronological:

```text
HA close = (open + high + low + close) / 4
HA open  = (previous HA open + previous HA close) / 2
HA high  = max(high, HA open, HA close)
HA low   = min(low, HA open, HA close)
```

For the first candle in the loaded history, HA open = (open + close) / 2. Each subsequent HA open uses the previous committed HA open and close. The HA candles are used for previous-candle body delta, current high/low, two-bars-ago high/low, FVG boundaries, and FVG invalidation. Toobit timestamps and volume are preserved. Live previews derive from the last committed HA candle and do not append to the recursive series until close. Raw Toobit OHLCV is retained separately only to detect source-feed revisions during REST reconciliation; it is never passed to strategy calculations.

## 2. Auto Threshold

LuxAlgo Chart-mode formula:

```text
delta_per = (previous_close - previous_open) / previous_open * 100
threshold = ta.cum(abs(change_tf ? delta_per : 0)) / bar_index * 2
```

With FVG in Chart mode (`fvg_tf = ""`), the selected chart timeframe is the only timeframe. For each committed candle JAVAD adds the absolute previous-candle percentage delta to the cumulative sum, divides by the current Pine-style `bar_index`, and multiplies by 2. The first candle has `bar_index = 0`, so its threshold is undefined, matching Pine. Every symbol has its own cumulative sum and bar index. Intrabar previews use rollback semantics and do not accumulate repeatedly; a candle close commits once.

## 3. Bullish and bearish FVG conditions

Bullish condition, all required simultaneously:

```text
currentLow > highTwoBarsAgo
previousClose > highTwoBarsAgo
deltaPercent > autoThreshold
timeframeChanged == true
```

Bearish condition, all required simultaneously:

```text
currentHigh < lowTwoBarsAgo
previousClose < lowTwoBarsAgo
-deltaPercent > autoThreshold
timeframeChanged == true
```

FVG alone cannot create a signal unless the corresponding Auto Threshold condition also passes. The three-candle gap comparison is part of the FVG formula, not a separate indicator. No EMA, RSI, ICT, or alternative strategy is added.

## 4. FVG boxes and invalidation

For a bullish gap, the upper boundary is the current candle low, the lower boundary is the high from two candles back, and the midpoint is their arithmetic mean. The paired boxes are removed when a later candle low is strictly below the lower boundary.

For a bearish gap, the upper boundary is the low from two candles back, the lower boundary is the current candle high, and the midpoint is their arithmetic mean. The paired boxes are removed using the source's strict comparison against the original bearish boundary.

JAVAD preserves the source's strict comparisons and paired-box state. The configured box extension is one chart bar, matching the source default.

## 5. One chart timeframe

There is exactly one selected `AnalysisTimeframe` per settings instance. It controls Toobit REST klines, WebSocket topic, candle duration and alignment, FVG Chart-mode OHLC offsets, and `timeframe.change`. Supported intervals are only 1m, 5m, 15m, 30m, 1h, and 2h. There is no independent FVG timeframe; 3h and 4h are not supported or synthesized.

Changing the selected timeframe clears each symbol's warmed-up processor, bar index, cumulative Auto Threshold, and FVG arrays. History must be loaded again before live analysis resumes.

## 6. Adaptive Toobit history window

The user-confirmed target counts are:

| Toobit interval | Target candle count |
|---|---:|
| 1m | 7,200 |
| 5m | 5,472 |
| 15m | 5,568 |
| 30m | 33,408 |
| 1h | 7,944 |
| 2h | 8,352 |

These are timeframe-specific target counts, not a common 5,000-candle cap. The calculation is independent for each selected symbol and uses Toobit's exchange server time.

For each start/restart/recovery:

1. Align the exchange server time down to the open time of the current/forming candle for the selected timeframe.
2. Request the latest fully closed candle from Toobit, whose open time is exactly one interval before the current candle open. Validate the returned timestamp.
3. Find the next UTC midnight strictly after the exchange server time.
4. Calculate remaining slots as `(nextUtcMidnight - currentCandleOpen) / timeframeDuration`. This includes the current forming slot and all later slots before midnight; the midnight timestamp itself is excluded.
5. Calculate the number of completed history candles as `targetCount - remainingSlots`. This count includes the latest fully closed candle.
6. Calculate the first historical open time as `latestClosedOpen - (completedCount - 1) * timeframeDuration`.
7. Fetch only the older candles from that first open time up to (but excluding) the latest closed candle's open time. Append the already fetched latest closed candle.
8. Require the exact expected count, correct first and last timestamps, selected-timeframe alignment, strict chronological order, no duplicates, and no gaps. If Toobit cannot supply the exact range, reject the history and do not start analysis with a shifted `bar_index`.

The REST end boundary is exclusive at the current candle open time. Thus a candle opening at 00:00 UTC is not included in the previous day's completed history. It is initially only a live preview; after it closes, it becomes a completed candle in the new day's history. The first historical candle is `bar_index = 0`, the first Heikin Ashi seed, and the origin of the cumulative Auto Threshold.

As time advances within the UTC day, the remaining-slot count decreases and the required completed-history count increases. At UTC midnight the remaining-slot count resets for the new day, so the calculated first-candle boundary advances by one day. If JAVAD was offline, it recalculates from the current Toobit latest closed candle and fetches the required backfill before resuming the live stream.

Toobit's kline endpoint limits each response to 1,000 rows. JAVAD uses paginated requests for the older history. Page boundaries never reset Heikin Ashi recursion, Pine `bar_index`, cumulative Auto Threshold, or FVG state. The resulting list is sorted oldest-first and validated before it is supplied to the analysis engine.

## 7. Live updates and recovery

- Every forming-candle update is evaluated as a Pine realtime preview/rollback update. Its high/low can make the raw FVG condition appear or disappear intrabar.
- A qualifying preview can emit a LONG/SHORT notification intrabar. Preview does not commit Heikin Ashi recursion, cumulative Auto value, `bar_index`, or FVG arrays.
- Every evaluation that satisfies the LuxAlgo FVG + Auto conditions can emit independently. No additional same-candle, same-direction, or last-signal suppression is applied.
- When the next candle begins, the preceding candle is committed once.
- Missing or out-of-order candles invalidate the affected symbol's processor. REST history must be reloaded before analysis resumes.
- Startup, reconnect, and gap recovery use the same adaptive history calculation. Periodic REST reconciliation compares the complete candle snapshot; a revised or missing candle triggers a full rebuild for that symbol.

## 8. Dataset parity boundary

The formulas and indexing behavior are mapped to the cited public LuxAlgo source mirror. Exact numerical parity with the Toobit TradingView chart is not certified merely because the formulas match. It requires comparing the chart and REST timestamps and OHLC values for the same contract/timeframe, including the first candle, all intermediate candles, timezone alignment, and any historical revisions.

The saved 5-minute TradingView comparison anchor is 2026-11-09 00:00 UTC. Earlier Toobit REST tests did not expose that full range. The adaptive loader now requests the exact user-confirmed target range and rejects incomplete history, but live API verification and chart-vs-REST candle comparison remain required before claiming exact numerical parity.

## 9. Signal delivery

Every selected contract has independent history, Heikin Ashi recursion, bar index, cumulative Auto Threshold, and FVG zones. A signal is produced only when the original FVG and Auto Threshold conditions are simultaneously true. No extra repeat-suppression condition is added.

## 10. Non-commercial attribution

The adapted LuxAlgo-derived calculation carries the source attribution and CC BY-NC-SA 4.0 notice in `engine/NOTICE.md`. The adaptation is marked non-commercial under that license.
