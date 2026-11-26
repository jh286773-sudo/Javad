/*
 * Adapted FVG + Auto Threshold calculation for Javad.
 * Original algorithm reference: Smart Money Concepts [LuxAlgo], © LuxAlgo.
 * Reference source: https://github.com/toikade/LuxAlgoScriptsAndOthers
 * License: CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/)
 * This adaptation is for non-commercial use and is provided under the same license.
 */
package com.javad.signal

import kotlin.math.abs

data class FvgInput(
    val previousOpen: Double,
    val previousClose: Double,
    val currentHigh: Double,
    val currentLow: Double,
    val highTwoBarsAgo: Double,
    val lowTwoBarsAgo: Double,
    val newFvgTimeframe: Boolean
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
 * Stateful port of the LuxAlgo SMC FVG/Auto Threshold calculation.
 * Feed every chart-timeframe bar in chronological order, starting from the
 * same historical starting point as the reference chart. Do not reset this
 * instance between bars: both ta.cum() and bar_index are cumulative.
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

        if (input.newFvgTimeframe) {
            cumulativeAbsoluteDelta += abs(deltaPercent)
        }

        // Pine's bar_index is zero on the first bar; division by zero yields na.
        val threshold = when {
            !autoThresholdEnabled -> 0.0
            index == 0L -> null
            else -> cumulativeAbsoluteDelta / index.toDouble() * 2.0
        }

        val usableThreshold = threshold ?: Double.POSITIVE_INFINITY
        val bullish = input.newFvgTimeframe &&
            input.currentLow > input.highTwoBarsAgo &&
            input.previousClose > input.highTwoBarsAgo &&
            deltaPercent > usableThreshold

        val bearish = input.newFvgTimeframe &&
            input.currentHigh < input.lowTwoBarsAgo &&
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
