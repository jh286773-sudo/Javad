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
 * Timeframe = Chart mode. The Auto Threshold denominator is Pine's chart
 * bar_index, not the number of bars in a locally fetched page. When processing
 * a partial history window, pass both its true first chart bar_index and the
 * cumulative absolute delta from all earlier chart bars. For a complete exchange
 * history beginning at its first available candle, both values are zero. Call once
 * for every chart bar, including bars where Pine history references are na.
 */
class LuxAlgoFvgAutoCalculator(
    private val autoThresholdEnabled: Boolean = true,
    firstChartBarIndex: Long = 0L,
    initialCumulativeAbsoluteDelta: Double = 0.0
) {
    init {
        require(firstChartBarIndex >= 0L) { "firstChartBarIndex must not be negative" }
        require(initialCumulativeAbsoluteDelta.isFinite() && initialCumulativeAbsoluteDelta >= 0.0) {
            "initialCumulativeAbsoluteDelta must be finite and non-negative"
        }
    }

    private var chartBarIndex: Long = firstChartBarIndex
    private var cumulativeAbsoluteDelta: Double = initialCumulativeAbsoluteDelta

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
    private val timeframe: AnalysisTimeframe,
    autoThresholdEnabled: Boolean = true,
    firstChartBarIndex: Long = 0L,
    initialCumulativeAbsoluteDelta: Double = 0.0
) {
    private val calculator = LuxAlgoFvgAutoCalculator(
        autoThresholdEnabled,
        firstChartBarIndex,
        initialCumulativeAbsoluteDelta
    )
    private val recentCandles = mutableListOf<MarketCandle>()
    private val expectedIntervalMillis = when (timeframe) {
        AnalysisTimeframe.ONE_MINUTE -> 60_000L
        AnalysisTimeframe.FIVE_MINUTES -> 5L * 60_000L
        AnalysisTimeframe.FIFTEEN_MINUTES -> 15L * 60_000L
        AnalysisTimeframe.THIRTY_MINUTES -> 30L * 60_000L
        AnalysisTimeframe.FORTY_FIVE_MINUTES -> 45L * 60_000L
        AnalysisTimeframe.ONE_HOUR -> 60L * 60_000L
        AnalysisTimeframe.TWO_HOURS -> 2L * 60L * 60_000L
    }

    fun process(candle: MarketCandle): FvgEvaluation {
        require(candle.openTime > 0L) { "Candle open time must be positive" }
        require(candle.openTime % expectedIntervalMillis == 0L) {
            "Candle open time is not aligned to the Toobit $timeframe interval"
        }
        val previous = recentCandles.lastOrNull()
        if (previous != null) {
            require(candle.openTime - previous.openTime == expectedIntervalMillis) {
                "Non-consecutive $timeframe candles: refusing to shift LuxAlgo bar index and Auto Threshold"
            }
        }
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


/** Keeps duplicate-suppression state independent for every selected contract. */
class PerSymbolDirectionSignalGate {
    private val gates = mutableMapOf<String, DirectionSignalGate>()

    fun accept(symbol: String, evaluation: FvgEvaluation): Signal? {
        val normalized = symbol.trim().uppercase()
        require(normalized.isNotEmpty()) { "Symbol must not be blank" }
        return gates.getOrPut(normalized) { DirectionSignalGate() }.accept(evaluation)
    }

    fun current(symbol: String): FvgDirection? =
        gates[symbol.trim().uppercase()]?.current()

    fun remove(symbol: String) {
        gates.remove(symbol.trim().uppercase())
    }
}
