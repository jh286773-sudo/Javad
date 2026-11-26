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
 * Null values represent unavailable history at the start of the chart.
 */
data class FvgInput(
    val previousOpen: Double?,
    val previousClose: Double?,
    val currentHigh: Double,
    val currentLow: Double,
    val highTwoBarsAgo: Double?,
    val lowTwoBarsAgo: Double?
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
 * Timeframe = Chart mode. Call once for every chart bar, including the first
 * bars where Pine history references are unavailable.
 */
class LuxAlgoFvgAutoCalculator(
    private val autoThresholdEnabled: Boolean = true
) {
    private var chartBarIndex: Long = 0L
    private var cumulativeAbsoluteDelta: Double = 0.0

    fun evaluate(input: FvgInput): FvgEvaluation {
        val values = listOf(
            input.currentHigh, input.currentLow,
            input.previousOpen, input.previousClose,
            input.highTwoBarsAgo, input.lowTwoBarsAgo
        )
        require(values.filterNotNull().all { it.isFinite() }) {
            "OHLC values must be finite"
        }
        require(input.previousOpen == null || input.previousOpen > 0.0) {
            "previousOpen must be greater than zero when available"
        }

        val index = chartBarIndex
        val deltaPercent = if (input.previousOpen != null && input.previousClose != null) {
            (input.previousClose - input.previousOpen) / input.previousOpen * 100.0
        } else null

        // ta.cum ignores unavailable source values; the first chart bar adds nothing.
        if (deltaPercent != null) cumulativeAbsoluteDelta += abs(deltaPercent)

        // Pine bar_index is zero on the first chart bar.
        val threshold = when {
            !autoThresholdEnabled -> 0.0
            index == 0L -> null
            else -> cumulativeAbsoluteDelta / index.toDouble() * 2.0
        }

        val bullish = deltaPercent != null &&
            threshold != null &&
            input.highTwoBarsAgo != null &&
            input.currentLow > input.highTwoBarsAgo &&
            input.previousClose!! > input.highTwoBarsAgo &&
            deltaPercent > threshold

        val bearish = deltaPercent != null &&
            threshold != null &&
            input.lowTwoBarsAgo != null &&
            input.currentHigh < input.lowTwoBarsAgo &&
            input.previousClose!! < input.lowTwoBarsAgo &&
            -deltaPercent > threshold

        chartBarIndex += 1L
        return FvgEvaluation(index, deltaPercent, threshold, bullish, bearish)
    }
}

/**
 * Maps real chronological Toobit candles to LuxAlgo's history references.
 * A completed candle at index i uses open/close from i-1 and high/low from i-2.
 */
class LuxAlgoFvgAutoProcessor(
    autoThresholdEnabled: Boolean = true
) {
    private val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled)
    private val recentCandles = mutableListOf<MarketCandle>()

    fun process(candle: MarketCandle): FvgEvaluation {
        val previous = recentCandles.lastOrNull()
        val twoBarsAgo = recentCandles.getOrNull(recentCandles.lastIndex - 1)

        val evaluation = calculator.evaluate(
            FvgInput(
                previousOpen = previous?.open,
                previousClose = previous?.close,
                currentHigh = candle.high,
                currentLow = candle.low,
                highTwoBarsAgo = twoBarsAgo?.high,
                lowTwoBarsAgo = twoBarsAgo?.low
            )
        )

        recentCandles += candle
        if (recentCandles.size > 2) recentCandles.removeAt(0)
        return evaluation
    }

    fun processHistory(candles: List<MarketCandle>): List<FvgEvaluation> =
        candles.map(::process)
}

/** Emits only a direction change; repeated same-direction FVGs are suppressed. */
class DirectionSignalGate {
    private var currentDirection: FvgDirection? = null

    fun accept(evaluation: FvgEvaluation): Signal? {
        if (evaluation.bullishFvg == evaluation.bearishFvg) return null

        val next = if (evaluation.bullishFvg) FvgDirection.LONG else FvgDirection.SHORT
        if (next == currentDirection) return null

        val delta = evaluation.deltaPercent ?: return null
        val threshold = evaluation.autoThreshold ?: return null
        currentDirection = next
        return Signal(next, evaluation.chartBarIndex, delta, threshold)
    }

    fun current(): FvgDirection? = currentDirection
}
