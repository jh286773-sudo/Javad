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
 * One completed candle from Javad's single selected analysis timeframe.
 *
 * This models LuxAlgo's Timeframe = Chart setting: the requested FVG
 * timeframe is the chart timeframe itself, so timeframe.change(fvg_tf)
 * is true once for each chart-timeframe bar. The caller must provide these
 * values in chronological order, with the same candle indexing/history
 * convention as the reference chart.
 */
data class FvgInput(
    val previousOpen: Double,
    val previousClose: Double,
    val currentHigh: Double,
    val currentLow: Double,
    val highTwoBarsAgo: Double,
    val lowTwoBarsAgo: Double
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
 * Timeframe = Chart mode.
 *
 * Call evaluate exactly once per chart-timeframe candle, in chronological
 * order, starting at the same historical bar_index origin as the reference.
 * In Chart mode timeframe.change(fvg_tf) is true on every chart bar, so each
 * bar's abs(deltaPercent) contributes to ta.cum(). Do not reset this
 * calculator between bars: both the cumulative sum and bar_index persist.
 */
class LuxAlgoFvgAutoCalculator(
    private val autoThresholdEnabled: Boolean = true
) {
    private var chartBarIndex: Long = 0L
    private var cumulativeAbsoluteDelta: Double = 0.0

    fun evaluate(input: FvgInput): FvgEvaluation {
        require(input.previousOpen.isFinite() && input.previousOpen > 0.0) {
            "previousOpen must be finite and greater than zero"
        }
        require(listOf(input.previousClose, input.currentHigh, input.currentLow,
            input.highTwoBarsAgo, input.lowTwoBarsAgo).all { it.isFinite() }) {
            "OHLC values must be finite"
        }

        val index = chartBarIndex
        val deltaPercent = (input.previousClose - input.previousOpen) /
            input.previousOpen * 100.0

        // Chart mode: timeframe.change(fvg_tf) is true for every chart bar.
        cumulativeAbsoluteDelta += abs(deltaPercent)

        // Pine's bar_index is zero on the first bar; division by zero yields na.
        val threshold = when {
            !autoThresholdEnabled -> 0.0
            index == 0L -> null
            else -> cumulativeAbsoluteDelta / index.toDouble() * 2.0
        }

        val usableThreshold = threshold ?: Double.POSITIVE_INFINITY
        val bullish = input.currentLow > input.highTwoBarsAgo &&
            input.previousClose > input.highTwoBarsAgo &&
            deltaPercent > usableThreshold

        val bearish = input.currentHigh < input.lowTwoBarsAgo &&
            input.previousClose < input.lowTwoBarsAgo &&
            -deltaPercent > usableThreshold

        chartBarIndex += 1L
        return FvgEvaluation(index, deltaPercent, threshold, bullish, bearish)
    }
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
