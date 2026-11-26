# JAVAD — FVG + Auto Threshold and chart-timeframe audit

## Scope

This audit covers only the LuxAlgo Smart Money Concepts Fair Value Gap (FVG) calculation with Auto Threshold enabled, its Chart-mode timeframe mapping, candle history indexing, and live-candle handling. It does not add EMA, RSI, ICT, or another strategy.

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

## 7. TradingView history window and bar index

TradingView's published intraday-history description states that the 5,000-bar limit is supplemented by bars back to the start of a calendar week, month, or year depending on the chart interval:
- https://www.tradingview.com/support/solutions/43000480679-historical-intraday-data-bars-and-limits-explained/

JAVAD models the 5,000-bar lookback and then aligns its start to the corresponding boundary:
- 1–14 minute intervals: beginning of week.
- 15–29 minute intervals: beginning of month.
- 30–1439 minute intervals: beginning of year.

For the current 5-minute comparison, the user identified 2026-11-09 00:00 UTC as the first candle. The candle opening at 2026-11-12 13:05 UTC is 1,021 five-minute intervals after that start. If the TradingView and JAVAD datasets both begin at that candle, that signal candle has Pine-style `bar_index = 1021`.

The first candle returned by the Toobit history loader is used as index 0 and the Auto Threshold cumulative sum starts from that dataset boundary. This is only numerically equivalent to TradingView if both data sets have the same first candle and the same OHLC values throughout the history.

## 8. Live updates, close handling, and data gaps

- Forming-candle updates are evaluated using preview/rollback semantics.
- A candle is committed only after the next source candle indicates that it has closed.
- Duplicate updates for the same candle do not commit cumulative values twice.
- Missing or out-of-order candles invalidate the affected symbol's processor; analysis must be warmed up again from REST history before continuing.
- Every selected contract has independent history, bar index, cumulative delta, FVG zones, and last delivered direction.
- Signal delivery is at most once per candle. Repeated same-direction signals are suppressed until a qualifying signal in the opposite direction appears.

## 9. Known parity boundary — not yet proven

The formulas and indexing behavior above are mapped to the cited public LuxAlgo source mirror. Exact numerical parity with the TradingView Toobit chart is **not yet proven**. It still requires comparing the timestamp and OHLC values for each candle, especially:
- The exact first historical candle available to both systems.
- Toobit REST OHLC versus TradingView's Toobit feed OHLC.
- UTC alignment and any chart timezone differences.
- Any historical candle revisions or feed-specific rounding.

The user's first-candle and signal timestamps establish an index expectation, not OHLC identity. The post-install phone comparison will be the validation stage. No tests or live trading actions are run as part of this code integration.
