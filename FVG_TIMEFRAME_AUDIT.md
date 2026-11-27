# JAVAD — FVG + Auto Threshold and chart-timeframe audit

## Scope

This audit covers only two components: the LuxAlgo Smart Money Concepts Fair Value Gap (FVG) calculation and Auto Threshold, plus their Chart-mode timeframe mapping, candle history indexing, and live-candle handling. FVG's gap test references three candles; those are price inputs within FVG, not three separate indicators. No EMA, RSI, ICT, or other strategy is included.

The implementation reference is the publicly available Smart Money Concepts [LuxAlgo] Pine source mirror at:
- https://github.com/toikade/LuxAlgoScriptsAndOthers/blob/main/SmartmoneyConcepts

This is a public mirror, not a claim that the file is the latest official LuxAlgo release. The adapted engine retains the source attribution and CC BY-NC-SA 4.0 notice in `engine/NOTICE.md`. This adaptation is marked non-commercial.

## 1. Source input mapping

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

## 2. Percentage delta

LuxAlgo formula:

```text
delta_per = (src_c1 - src_o1) / src_o1 * 100
```

JAVAD formula:

```text
(previousClose - previousOpen) / previousOpen * 100
```

The previous candle must exist and its open must be positive. On the first chart candle, the previous-candle values are unavailable, so delta and threshold are undefined, matching Pine's `na` behavior.

## 3. Auto Threshold

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

## 4. Bullish and bearish FVG conditions

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

## 5. FVG box boundaries and invalidation

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

## 6. One chart timeframe across the entire data path

There is exactly one selected `AnalysisTimeframe` per JAVAD settings instance. The same interval controls:
- Toobit REST kline interval used for historical candles.
- Toobit WebSocket topic used for live candles.
- Candle duration and timestamp alignment checks.
- FVG Chart-mode OHLC offsets and `timeframe.change`.

There is no separate FVG timeframe. Changing the selected chart timeframe clears each symbol's warmed-up processor, bar index, cumulative Auto Threshold, FVG arrays, and signal-direction memory. History must be loaded again before live analysis resumes.

Supported chart intervals are 1m, 5m, 15m, 30m, 1h, and 2h. The initial default is 5m for the current BTCUSDT.P comparison.

## 7. Toobit history window and bar index

TradingView's Pine execution model calculates the script over the bars available in the chart dataset. The dataset size is constrained by TradingView's plan and chart-history availability; that limit is not a fixed part of LuxAlgo's Auto Threshold formula.

JAVAD does not use TradingView's Basic-plan 5,000-bar window. Toobit's documented `/quote/v1/klines` endpoint supports `startTime`, `endTime`, and a maximum page size of 1,000 rows:
- https://api-docs.toobit.com/api/usdt-m-market-data.html

Live testing showed that a broad request returns only the newest page, not the entire range. JAVAD therefore requests bounded forward windows for explicit ranges and discovers full history by paging backward from the latest completed candle until Toobit returns no older page. The result is sorted into ascending order before the engine starts. No arbitrary 5,000-candle cap is applied. The earliest candle actually returned by the endpoint becomes Pine-style `bar_index = 0`, and the cumulative Auto Threshold starts there. Page boundaries do not reset either `bar_index` or `ta.cum`; missing or duplicate intervals cause history loading to fail rather than silently changing the calculation.

A live Toobit test on 2026-11-27 returned 3,499 completed 5-minute candles for each of BTCUSDT.P and ETHUSDT.P, across four pages. In one successful run on 2026-11-27, the first returned candle was 2026-11-15 04:55 UTC and the last was 2026-11-27 08:25 UTC. This is the history returned by the tested public endpoint at that time, not a claim that every TradingView chart has the same dataset.

The saved TradingView comparison anchor for this project is 2026-11-09 00:00 UTC. That is 1,787 five-minute bars earlier than the first candle returned by the latest live Toobit API test. Because Auto Threshold depends on both `bar_index` and the cumulative absolute delta, the different starting candle can produce different thresholds and signals even when the formulas are identical. Exact numerical parity with TradingView still requires the same first candle and OHLC values throughout the dataset, plus matching timezone and candle availability.

## 8. Live updates, closed-candle signal timing, and data gaps

- Every forming-candle update is evaluated as a Pine realtime preview/rollback update. The current candle's high/low can make the raw FVG condition appear or disappear intrabar.
- A qualifying preview can emit a LONG/SHORT notification intrabar. Preview does not commit cumulative Auto value, `bar_index`, or FVG arrays; the later closed snapshot commits them once. If the same candle already emitted, its close does not create a duplicate notification.
- Each new qualifying candle can emit independently, including consecutive signals in the same direction. Only duplicate notifications for the same candle are suppressed.
- Toobit does not provide a separate close flag in this stream path: when the first update for the next candle arrives, JAVAD treats the preceding candle as closed and commits its calculation state.
- Missing or out-of-order candles invalidate the affected symbol's processor; analysis must be warmed up again from REST history before continuing.
- Every selected contract has independent history, bar index, cumulative delta, FVG zones, and per-candle notification memory.

## 9. Known parity boundary — not yet proven

The formulas and indexing behavior above are mapped to the cited public LuxAlgo source mirror. Exact numerical parity with the TradingView Toobit chart is **not yet proven**. It still requires comparing the timestamp and OHLC values for each candle, especially:
- The exact first historical candle available to both systems.
- Toobit REST OHLC versus TradingView's Toobit feed OHLC.
- UTC alignment and any chart timezone differences.
- Any historical candle revisions or feed-specific rounding.

The user's first-candle and signal timestamps establish an index expectation, not OHLC identity. Automated tests now cover the Auto formula, bullish/bearish FVG conditions, paired FVG box boundaries and invalidation, 3,500-candle page-boundary equivalence, live BTC/ETH Toobit history, and per-candle signal behavior. No live trading actions are part of the test suite.
