/*
 * Adapted FVG + Auto Threshold calculation for Javad.
 * Original algorithm reference: Smart Money Concepts [LuxAlgo], © LuxAlgo.
 * Reference source: https://github.com/toikade/LuxAlgoScriptsAndOthers
 * License: CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/)
 * This adaptation is for non-commercial use and is provided under the same license.
 */
package com.javad.signal

import kotlin.math.abs

/**
 * One chart bar's exact inputs for LuxAlgo get_ohlc():
 * close[1], open[1], high, low, high[2], low[2].
 *
 * In LuxAlgo Chart mode (fvg_tf = ""), timeframeChanged is true on each
 * chart bar. It is explicit here so the source condition is not silently
 * replaced by an assumption inside the formula.
 */
data class FvgInput(
    val previousOpen: Double?,
    val previousClose: Double?,
    val currentHigh: Double,
    val currentLow: Double,
    val highTwoBarsAgo: Double?,
    val lowTwoBarsAgo: Double?,
    val timeframeChanged: Boolean = true
)

enum class FvgDirection { LONG, SHORT }

data class FvgEvaluation(
    val chartBarIndex: Long,
    val deltaPercent: Double?,
    val autoThreshold: Double?,
    val bullishFvg: Boolean,
    val bearishFvg: Boolean
)

data class Signal(
    val direction: FvgDirection,
    val chartBarIndex: Long,
    val deltaPercent: Double,
    val autoThreshold: Double
)

/**
 * Stateful port of LuxAlgo SMC's FVG/Auto Threshold calculation in
 * Timeframe = Chart mode (fvg_tf = ""). The selected Javad timeframe is the
 * chart timeframe and therefore change_tf is true for every chart bar.
 *
 * Auto Threshold mirrors:
 * ta.cum(math.abs(change_tf ? delta_per : 0)) / bar_index * 2
 *
 * The first candle in the Toobit history supplied to Javad is bar_index 0,
 * exactly as Pine numbers the first candle in its loaded chart dataset. The
 * cumulative absolute delta also starts at zero for that dataset.
 */
class LuxAlgoFvgAutoCalculator(
    private val autoThresholdEnabled: Boolean = true
) {
    // Pine bar_index starts at zero on the first candle in the loaded dataset.
    private var chartBarIndex: Long = 0L
    private var cumulativeAbsoluteDelta: Double = 0.0

    /** Evaluate a live chart-bar update using Pine realtime rollback semantics. */
    fun preview(input: FvgInput): FvgEvaluation = calculate(input, commit = false)

    /** Commit one completed chart bar to ta.cum and advance bar_index exactly once. */
    fun evaluate(input: FvgInput): FvgEvaluation = calculate(input, commit = true)

    private fun calculate(input: FvgInput, commit: Boolean): FvgEvaluation {
        val values = listOf(
            input.currentHigh, input.currentLow,
            input.previousOpen, input.previousClose,
            input.highTwoBarsAgo, input.lowTwoBarsAgo
        )
        require(values.filterNotNull().all { it.isFinite() }) { "OHLC values must be finite" }
        require(input.previousOpen == null || input.previousOpen > 0.0) {
            "previousOpen must be greater than zero when available"
        }

        val index = chartBarIndex
        val deltaPercent = if (input.previousOpen != null && input.previousClose != null) {
            (input.previousClose - input.previousOpen) / input.previousOpen * 100.0
        } else null

        // Direct equivalent of: math.abs(change_tf ? delta_per : 0).
        // Preview is recalculated from the last committed sum on every tick.
        val deltaContribution = if (input.timeframeChanged) {
            deltaPercent?.let(::abs) ?: 0.0
        } else {
            0.0
        }
        val candidateCumulative = cumulativeAbsoluteDelta + deltaContribution

        // Direct equivalent of: fvg_auto ? ta.cum(...) / n * 2 : 0.
        // bar_index is zero on the first chart bar, where the threshold is na.
        val threshold = when {
            !autoThresholdEnabled -> 0.0
            index == 0L -> null
            else -> candidateCumulative / index.toDouble() * 2.0
        }

        val bullish = input.timeframeChanged &&
            deltaPercent != null &&
            threshold != null &&
            input.highTwoBarsAgo != null &&
            input.currentLow > input.highTwoBarsAgo &&
            input.previousClose!! > input.highTwoBarsAgo &&
            deltaPercent > threshold

        val bearish = input.timeframeChanged &&
            deltaPercent != null &&
            threshold != null &&
            input.lowTwoBarsAgo != null &&
            input.currentHigh < input.lowTwoBarsAgo &&
            input.previousClose!! < input.lowTwoBarsAgo &&
            -deltaPercent > threshold

        if (commit) {
            cumulativeAbsoluteDelta = candidateCumulative
            chartBarIndex += 1L
        }
        return FvgEvaluation(index, deltaPercent, threshold, bullish, bearish)
    }
}

/**
 * Maps chronological Toobit chart candles to LuxAlgo's history references.
 * On chart candle i: close[1]/open[1] come from i-1, high/low from i,
 * and high[2]/low[2] come from i-2.
 */
class LuxAlgoFvgAutoProcessor(
    private val chartTimeframe: AnalysisTimeframe,
    autoThresholdEnabled: Boolean = true
) {
    private val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled)
    private val recentCandles = mutableListOf<MarketCandle>()
    private val expectedIntervalMillis = chartTimeframe.durationMillis

    /** Chart mode: each update belongs to the selected chart timeframe. */
    fun preview(candle: MarketCandle): FvgEvaluation =
        calculator.preview(inputFor(candle))

    /** Commits one completed chart bar exactly once. */
    fun process(candle: MarketCandle): FvgEvaluation {
        val evaluation = calculator.evaluate(inputFor(candle))
        recentCandles += candle
        if (recentCandles.size > 2) recentCandles.removeAt(0)
        return evaluation
    }

    private fun inputFor(candle: MarketCandle): FvgInput {
        require(candle.openTime > 0L) { "Candle open time must be positive" }
        require(candle.openTime % expectedIntervalMillis == 0L) {
            "Candle open time is not aligned to the selected chart interval $chartTimeframe"
        }
        val previous = recentCandles.lastOrNull()
        if (previous != null) {
            require(candle.openTime - previous.openTime == expectedIntervalMillis) {
                "Non-consecutive $chartTimeframe chart candles: refusing to shift LuxAlgo bar index and Auto Threshold"
            }
        }
        val twoBarsAgo = recentCandles.getOrNull(recentCandles.lastIndex - 1)
        return FvgInput(
            previousOpen = previous?.open,
            previousClose = previous?.close,
            currentHigh = candle.high,
            currentLow = candle.low,
            highTwoBarsAgo = twoBarsAgo?.high,
            lowTwoBarsAgo = twoBarsAgo?.low,
            timeframeChanged = true // timeframe.change("") in Chart mode
        )
    }

    fun processHistory(candles: List<MarketCandle>): List<FvgEvaluation> =
        candles.map(::process)
}
