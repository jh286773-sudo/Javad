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
    val bearishFvg: Boolean,
    /** Current LuxAlgo FVG-box state after this bar/tick is evaluated. */
    val fvgState: FvgStateUpdate? = null
)

/** One of the two rectangles LuxAlgo creates for each FVG. */
data class FvgBox(
    val top: Double,
    val bottom: Double
)

/**
 * The paired boxes created by the LuxAlgo source for one FVG.
 * left/right coordinates preserve box.new(n - 1, ..., n + fvg_extend, ...).
 */
data class FvgZone(
    val direction: FvgDirection,
    val chartBarIndex: Long,
    val leftBarIndex: Long,
    val rightBarIndex: Long,
    val maxBox: FvgBox,
    val minBox: FvgBox
)

/**
 * Snapshot of the FVG arrays after evaluating a bar/tick.
 * formedZones and removedZones are the changes on this evaluation;
 * activeZones is the resulting array state.
 */
data class FvgStateUpdate(
    val activeZones: List<FvgZone>,
    val formedZones: List<FvgZone>,
    val removedZones: List<FvgZone>
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
    private val autoThresholdEnabled: Boolean = true,
    firstChartBarIndex: Long = 0L,
    initialCumulativeAbsoluteDelta: Double = 0.0
) {
    // Allows a paginated/partial history loader to resume the exact Pine state.
    private var chartBarIndex: Long = firstChartBarIndex
    private var cumulativeAbsoluteDelta: Double = initialCumulativeAbsoluteDelta

    init {
        require(firstChartBarIndex >= 0L) { "firstChartBarIndex cannot be negative" }
        require(initialCumulativeAbsoluteDelta.isFinite() && initialCumulativeAbsoluteDelta >= 0.0) {
            "initialCumulativeAbsoluteDelta must be finite and non-negative"
        }
    }

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
 * Maps chronological Toobit chart candles to LuxAlgo's history references and
 * reproduces the source's paired FVG boxes and invalidation loops.
 *
 * Realtime preview starts from the last committed arrays on every tick,
 * matching Pine rollback. Only a closed candle commits the arrays and the
 * cumulative Auto Threshold/bar_index state.
 */
class LuxAlgoFvgAutoProcessor(
    private val chartTimeframe: AnalysisTimeframe,
    autoThresholdEnabled: Boolean = true,
    private val fvgExtend: Int = 1,
    firstChartBarIndex: Long = 0L,
    initialCumulativeAbsoluteDelta: Double = 0.0,
    private val useHeikinAshi: Boolean = false
) {
    private val calculator = LuxAlgoFvgAutoCalculator(
        autoThresholdEnabled = autoThresholdEnabled,
        firstChartBarIndex = firstChartBarIndex,
        initialCumulativeAbsoluteDelta = initialCumulativeAbsoluteDelta
    )
    private val recentCandles = mutableListOf<MarketCandle>()
    private val bullishZones = mutableListOf<FvgZone>()
    private val bearishZones = mutableListOf<FvgZone>()
    private val expectedIntervalMillis = chartTimeframe.durationMillis

    init {
        require(fvgExtend >= 0) { "FVG extension cannot be negative" }
    }

    /** Evaluate a live chart-bar update without committing Pine var/array state. */
    fun preview(candle: MarketCandle): FvgEvaluation =
        evaluateCandle(candle, commit = false)

    /** Commit one completed chart bar exactly once. */
    fun process(candle: MarketCandle): FvgEvaluation =
        evaluateCandle(candle, commit = true)

    /** Returns the last committed FVG arrays, in newest-first source order. */
    fun activeFvgZones(): List<FvgZone> = bullishZones.toList() + bearishZones.toList()

    private fun evaluateCandle(candle: MarketCandle, commit: Boolean): FvgEvaluation {
        // Convert exchange OHLC to Heikin Ashi before every strategy input.
        // recentCandles stores committed analysis candles, so live previews
        // always derive from the same prior HA candle (Pine-style rollback).
        val analysisCandle = toAnalysisCandle(candle)
        val input = inputFor(analysisCandle)
        val base = if (commit) calculator.evaluate(input) else calculator.preview(input)

        // Pine iterates the existing arrays and deletes the paired boxes when
        // the source's exact wick invalidation condition becomes true.
        val nextBullish = bullishZones.toMutableList()
        val nextBearish = bearishZones.toMutableList()
        val removed = mutableListOf<FvgZone>()

        val removedBullish = nextBullish.filter { analysisCandle.low < it.minBox.bottom }
        nextBullish.removeAll(removedBullish.toSet())
        removed += removedBullish

        // LuxAlgo checks high > box.get_top(bearish_fvg_max), which is src_h
        // (the lower boundary of the bearish gap), not its upper boundary.
        val removedBearish = nextBearish.filter { analysisCandle.high > it.maxBox.top }
        nextBearish.removeAll(removedBearish.toSet())
        removed += removedBearish

        val formed = mutableListOf<FvgZone>()
        if (base.bullishFvg) {
            val oldHigh = input.highTwoBarsAgo!!
            val midpoint = (analysisCandle.low + oldHigh) / 2.0
            formed += FvgZone(
                direction = FvgDirection.LONG,
                chartBarIndex = base.chartBarIndex,
                leftBarIndex = base.chartBarIndex - 1L,
                rightBarIndex = base.chartBarIndex + fvgExtend,
                maxBox = FvgBox(top = analysisCandle.low, bottom = midpoint),
                minBox = FvgBox(top = midpoint, bottom = oldHigh)
            )
        }

        if (base.bearishFvg) {
            val oldLow = input.lowTwoBarsAgo!!
            val midpoint = (analysisCandle.high + oldLow) / 2.0
            formed += FvgZone(
                direction = FvgDirection.SHORT,
                chartBarIndex = base.chartBarIndex,
                leftBarIndex = base.chartBarIndex - 1L,
                rightBarIndex = base.chartBarIndex + fvgExtend,
                maxBox = FvgBox(top = analysisCandle.high, bottom = midpoint),
                minBox = FvgBox(top = midpoint, bottom = oldLow)
            )
        }

        // array.unshift() in the Pine source: newest FVG is first in each array.
        val formedBullish = formed.filter { it.direction == FvgDirection.LONG }
        val formedBearish = formed.filter { it.direction == FvgDirection.SHORT }
        nextBullish.addAll(0, formedBullish)
        nextBearish.addAll(0, formedBearish)
        val state = FvgStateUpdate(
            activeZones = nextBullish + nextBearish,
            formedZones = formed,
            removedZones = removed
        )

        if (commit) {
            bullishZones.clear()
            bullishZones.addAll(nextBullish)
            bearishZones.clear()
            bearishZones.addAll(nextBearish)
            recentCandles += analysisCandle
            if (recentCandles.size > 2) recentCandles.removeAt(0)
        }

        return base.copy(fvgState = state)
    }

    /**
     * Builds a standard Heikin Ashi candle from the Toobit exchange candle.
     * The first HA open is (open + close) / 2; subsequent HA opens are the
     * average of the previous committed HA open and close. HA close is the
     * average of the current standard OHLC; HA wicks include HA open/close.
     */
    private fun toAnalysisCandle(candle: MarketCandle): MarketCandle {
        if (!useHeikinAshi) return candle
        val previousHeikinAshi = recentCandles.lastOrNull()
        val heikinAshiClose = (candle.open + candle.high + candle.low + candle.close) / 4.0
        val heikinAshiOpen = if (previousHeikinAshi == null) {
            (candle.open + candle.close) / 2.0
        } else {
            (previousHeikinAshi.open + previousHeikinAshi.close) / 2.0
        }
        return MarketCandle(
            openTime = candle.openTime,
            open = heikinAshiOpen,
            high = maxOf(candle.high, heikinAshiOpen, heikinAshiClose),
            low = minOf(candle.low, heikinAshiOpen, heikinAshiClose),
            close = heikinAshiClose,
            volume = candle.volume
        )
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
