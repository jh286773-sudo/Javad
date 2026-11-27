# JAVAD — FVG + Auto Threshold and chart-timeframe audit

## Scope

This audit covers only two components: the LuxAlgo Smart Money Concepts Fair Value Gap (FVG) calculation and Auto Threshold, plus their Chart-mode timeframe mapping, candle history indexing, and live-candle handling. FVG's gap test references three candles; those are price inputs within FVG, not three separate indicators. No EMA, RSI, ICT, or other strategy is included.

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

For the first candle in the loaded history, HA open = (open + close) / 2. The transformed HA candles are used for the previous-candle body delta, current high/low, two-bars-ago high/low, FVG box boundaries, and FVG invalidation. Toobit timestamps and volume are preserved. Live previews derive from the last committed HA candle and do not advance the recursive HA state; a closed candle commits once. Thus both FVG and Auto Threshold operate on Heikin Ashi, not merely a Heikin Ashi chart display.

## 2. Source input mapping

The Pine helper `get_ohlc()` returns:

| Pine source value | Meaning | JAVAD input |
|---|---|---|
| `close[1]` | Previous candle close | `previousClose` |
| `open[1]` | Previous candle open | `previousOpen` |
| `high` | Current chart candle high | `currentHigh` |
| `low` | Current chart candle low | `currentLow` |
| `high[2]` | High two candles back | `highTwoBarsAgo` |
| `low[2]` | Low two candles back | `lowTwoBarsAgo` |

JAVAD keeps the last two committed chart candles per symbol to supply these offsets. Intrabar previews use the same committed history and do not advance the index or cumulative sum.

## 3. Percentage delta

LuxAlgo formula:

```text
delta_per = (src_c1 - src_o1) / src_o1 * 100
```

JAVAD formula:

```text
(previousClose - previousOpen) / previousOpen * 100
```

The previous candle must exist and its open must be positive. On the first chart candle, the previous-candle values are unavailable, so delta and threshold are undefined, matching Pine's `na` behavior.

## 4. Auto Threshold

LuxAlgo Chart-mode formula:

```text
threshold = ta.cum(abs(change_tf ? delta_per : 0)) / bar_index * 2
```

With `fvg_tf = ""` (Chart mode), `timeframe.change(fvg_tf)` is true on each chart bar. Therefore, for every committed bar:

1. Take the absolute value of that bar's previous-candle percentage delta.
2. Add it to the cumulative absolute-delta sum.
3. Divide the cumulative sum, including the current bar's contribution, by the current Pine `bar_index`.
4. Multiply by 2.

At `bar_index = 0`, the division is undefined; JAVAD returns a null threshold for the first candle. With Auto Threshold disabled, the source threshold is zero.

Every symbol has its own cumulative sum and bar index. Repeated live updates to one forming candle are previews from the last committed state; they are not accumulated repeatedly. A candle close commits once and advances the index once.

## 5. Bullish and bearish FVG conditions

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

No standalone three-candle gap can create a signal unless the corresponding Auto Threshold condition also passes.

## 6. FVG box boundaries and invalidation

For a bullish gap:
- Upper boundary: current candle low.
- Lower boundary: high from two candles back.
- Midpoint: arithmetic mean of those boundaries.
- LuxAlgo creates two boxes split at the midpoint.
- The pair is removed when a later candle's low is strictly below the lower boundary.

For a bearish gap:
- Upper boundary: low from two candles back.
- Lower boundary: current candle high.
- Midpoint: arithmetic mean of those boundaries.
- LuxAlgo creates two boxes split at the midpoint.
- The pair is removed when a later candle's high is strictly above the original current-candle-high boundary represented by the top of the source `bearish_fvg_max` box.

JAVAD preserves the source's strict comparisons and paired-box state. The configured box extension is one chart bar, matching the source default `fvg_extend = 1`.

## 7. One chart timeframe across the entire data path

There is exactly one selected `AnalysisTimeframe` per JAVAD settings instance. The same interval controls:
- Toobit REST kline interval used for historical candles.
- Toobit WebSocket topic used for live candles.
- Candle duration and timestamp alignment checks.
- FVG Chart-mode OHLC offsets and `timeframe.change`.

There is no separate FVG timeframe. Changing the selected chart timeframe clears each symbol's warmed-up processor, bar index, cumulative Auto Threshold, FVG arrays, and signal-direction memory. History must be loaded again before live analysis resumes.

Supported chart intervals are 1m, 5m, 15m, 30m, 1h, and 2h. The initial default is 5m for the current BTCUSDT.P comparison.

## 8. Toobit history window and bar index

TradingView's Pine execution model calculates the script over the bars available in the chart dataset. The dataset size is constrained by TradingView's plan and chart-history availability; that limit is not a fixed part of LuxAlgo's Auto Threshold formula.

JAVAD does not use TradingView's Basic-plan 5,000-bar window. Toobit's documented `/quote/v1/klines` endpoint supports `startTime`, `endTime`, and a maximum page size of 1,000 rows:
- https://api-docs.toobit.com/api/usdt-m-market-data.html

Live testing showed that a broad request returns only the newest page, not the entire range. JAVAD therefore requests bounded forward windows for explicit ranges and discovers full history by paging backward from the latest completed candle until Toobit returns no older page. The result is sorted into ascending order before the engine starts. No arbitrary 5,000-candle cap is applied. The earliest candle actually returned by the endpoint becomes Pine-style `bar_index = 0`, and the cumulative Auto Threshold starts there. Page boundaries do not reset either `bar_index` or `ta.cum`; missing or duplicate intervals cause history loading to fail rather than silently changing the calculation.

A live Toobit test on 2026-11-27 returned 3,499 completed 5-minute candles for each of BTCUSDT.P and ETHUSDT.P, across four pages. In one successful run on 2026-11-27, the first returned candle was 2026-11-15 04:55 UTC and the last was 2026-11-27 08:25 UTC. This is the history returned by the tested public endpoint at that time, not a claim that every TradingView chart has the same dataset.

The saved TradingView comparison anchor for this project is 2026-11-09 00:00 UTC. That is 1,787 five-minute bars earlier than the first candle returned by the latest live Toobit API test. Because Auto Threshold depends on both `bar_index` and the cumulative absolute delta, the different starting candle can produce different thresholds and signals even when the formulas are identical. Exact numerical parity with TradingView still requires the same first candle and OHLC values throughout the dataset, plus matching timezone and candle availability.

## 9. Live updates, closed-candle signal timing, and data gaps

- Every forming-candle update is evaluated as a Pine realtime preview/rollback update. The current candle's high/low can make the raw FVG condition appear or disappear intrabar.
- A qualifying preview can emit a LONG/SHORT notification intrabar. Preview does not commit cumulative Auto value, `bar_index`, or FVG arrays; the later closed snapshot commits them once. If the same candle already emitted, its close does not create a duplicate notification.
- Each new qualifying candle can emit independently, including consecutive signals in the same direction. Only duplicate notifications for the same candle are suppressed.
- Toobit does not provide a separate close flag in this stream path: when the first update for the next candle arrives, JAVAD treats the preceding candle as closed and commits its calculation state.
- Missing or out-of-order candles invalidate the affected symbol's processor; analysis must be warmed up again from REST history before continuing.
- Every selected contract has independent history, bar index, cumulative delta, FVG zones, and per-candle notification memory.

## 10. Known parity boundary — not yet proven

The formulas and indexing behavior above are mapped to the cited public LuxAlgo source mirror. Exact numerical parity with the TradingView Toobit chart is **not yet proven**. It still requires comparing the timestamp and OHLC values for each candle, especially:
- The exact first historical candle available to both systems.
- Toobit REST OHLC versus TradingView's Toobit feed OHLC.
- UTC alignment and any chart timezone differences.
- Any historical candle revisions or feed-specific rounding.

The user's first-candle and signal timestamps establish an index expectation, not OHLC identity. Automated tests now cover the Auto formula, bullish/bearish FVG conditions, paired FVG box boundaries and invalidation, 3,500-candle page-boundary equivalence, live BTC/ETH Toobit history, and per-candle signal behavior. No live trading actions are part of the test suite.

## 11. Mandatory dataset-parity requirement (JAVAD acceptance gate)

The required reference is the complete candle dataset visible in the Toobit chart for the exact same contract and selected chart timeframe: from that chart dataset's first candle through its latest candle. This is not a fixed count and must never be replaced by a 5,000-candle or other arbitrary window.

The production loader must:
1. Discover and load the complete available history for each contract/timeframe independently, using pagination without resetting the Heikin Ashi recursion, Pine `bar_index`, or `ta.cum` state at page boundaries.
2. Sort candles chronologically and reject duplicate, missing, malformed, or out-of-order candles.
3. Use the first candle of the verified chart dataset as `bar_index = 0`; initialize Heikin Ashi from that same first candle and start the Auto cumulative calculation there.
4. Calculate both FVG and Auto Threshold from the same Heikin Ashi series, using the LuxAlgo Chart-mode formulas documented above.
5. Keep the final closed candle and live forming candle behavior consistent with Pine realtime rollback/commit semantics.

**Acceptance is not met merely because the REST endpoint returned every page it exposed.** The earliest and latest timestamps and candle values must be compared against the Toobit chart dataset for the same symbol/timeframe. If the REST endpoint cannot expose the chart's full dataset, JAVAD must report that boundary difference and must not claim exact chart/LuxAlgo parity. The current live REST test verifies pagination continuity and calculation consistency; it does not independently prove that REST history has the same first candle as the Toobit chart.

## 12. Automatic first/last candle boundary refresh (required behavior)

This rule applies independently to every selected Toobit perpetual contract and the one selected chart timeframe. It is not tied to the time JAVAD was installed, started, or last kept online.

- **When analysis starts or restarts:** fetch and paginate the symbol/timeframe's available history again. Rediscover its earliest available candle and latest completed candle; do not assume a previously saved start timestamp is still the earliest.
- **While analysis is running:** use the live kline stream to advance the latest boundary. Commit each newly closed candle once; keep the currently forming candle as a preview. Periodically reconcile the latest REST candles with the stream so missed updates are recovered.
- **If JAVAD was offline:** on the next start/reconnect, backfill every missing candle between the last locally known point and the latest completed candle before resuming live analysis. Never skip the offline interval.
- **If Toobit exposes older history than before:** the newly discovered earlier candle becomes the dataset start. Rebuild that symbol's Heikin Ashi series, `bar_index`, cumulative Auto Threshold, FVG state, and signal warm-up from the new first candle in chronological order. Do not prepend candles while retaining the old recursive/cumulative state.
- **If the earliest available candle has not changed:** retain the same dataset origin; only append/reconcile newer candles. A normal new day does not by itself move the first-candle boundary.
- **If the selected symbol or timeframe changes:** independently discover and load the complete history for that new selection before enabling its live analysis.

The moving latest boundary and the discovered earliest boundary must always be recorded with their candle timestamps per symbol/timeframe. A refresh is successful only after timestamp continuity and duplicate/order validation. The first boundary is stable unless the exchange makes older data available; the last boundary advances as new candles close.

**Implementation/verification status:** the current engine loads full REST history at startup and reloads after detected stream gaps. The automatic rediscovery of an earlier first boundary, periodic REST reconciliation of the latest boundary, and offline backfill must be verified in runtime tests before this entire lifecycle can be marked complete. The chart-vs-REST first/last timestamp and OHLC comparison remains a separate acceptance gate.
