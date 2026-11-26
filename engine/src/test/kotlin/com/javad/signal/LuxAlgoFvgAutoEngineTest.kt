package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

class LuxAlgoFvgAutoEngineTest {
    @Test
    fun autoThresholdUsesEveryChartBarCumulativeAbsoluteDeltaAndBarIndex() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = true)
        val first = calculator.evaluate(input(open = 100.0, close = 100.1))
        val second = calculator.evaluate(input(open = 100.0, close = 100.1))
        val third = calculator.evaluate(input(open = 100.0, close = 100.1))
        val fourth = calculator.evaluate(input(open = 100.0, close = 106.0))

        assertNull(first.autoThreshold) // bar_index == 0, Pine result is na
        assertEquals(0.4, second.autoThreshold!!, 1e-9)
        assertEquals(0.3, third.autoThreshold!!, 1e-9)
        assertEquals(4.2, fourth.autoThreshold!!, 1e-9)
        assertTrue(fourth.bullishFvg)
    }


    @Test
    fun autoThresholdUsesSuppliedPineChartBarIndexForPartialHistoryWindows() {
        val calculator = LuxAlgoFvgAutoCalculator(
            autoThresholdEnabled = true,
            firstChartBarIndex = 1_000L,
            initialCumulativeAbsoluteDelta = 10.0
        )

        val result = calculator.evaluate(input(open = 100.0, close = 101.0))

        // LuxAlgo: (prior cumulative |delta| + current |delta|) / bar_index * 2.
        // A partial page must preserve both Pine's bar_index and prior ta.cum value.
        assertEquals(0.022, result.autoThreshold!!, 1e-12)
        assertEquals(1_000L, result.chartBarIndex)
    }

    @Test
    fun realtimePreviewUsesPineRollbackAndDoesNotDoubleAccumulateOrAdvanceBarIndex() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = true)
        calculator.evaluate(input(open = 100.0, close = 101.0))

        val liveBar = input(open = 100.0, close = 105.0)
        val firstPreview = calculator.preview(liveBar)
        val secondPreview = calculator.preview(liveBar)
        val committed = calculator.evaluate(liveBar)
        val nextBar = calculator.evaluate(input(open = 100.0, close = 101.0))

        assertEquals(1L, firstPreview.chartBarIndex)
        assertEquals(firstPreview.autoThreshold, secondPreview.autoThreshold)
        assertEquals(firstPreview.autoThreshold, committed.autoThreshold)
        assertEquals(2L, nextBar.chartBarIndex)
        // 1% committed + 5% current bar, divided by bar_index 1, times 2.
        assertEquals(12.0, committed.autoThreshold!!, 1e-9)
        // The 5% live-bar contribution was committed once, not once per preview.
        assertEquals(7.0, nextBar.autoThreshold!!, 1e-9)
    }

    @Test
    fun autoThresholdAccumulatesAbsoluteNegativeAndPositiveDeltas() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = true)
        calculator.evaluate(input(open = 100.0, close = 101.0))
        val second = calculator.evaluate(input(open = 100.0, close = 99.0))

        // |+1%| + |-1%|, divided by bar_index 1, then multiplied by 2.
        assertEquals(4.0, second.autoThreshold!!, 1e-9)
    }

    @Test
    fun bearishFvgUsesPreviousCandleDeltaAndTwoBarsAgoLow() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = false)
        val result = calculator.evaluate(
            input(open = 100.0, close = 94.0).copy(
                currentHigh = 95.0,
                lowTwoBarsAgo = 96.0
            )
        )
        assertTrue(result.bearishFvg)
    }

    @Test
    fun disabledAutoThresholdUsesZeroInChartMode() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = false)
        val result = calculator.evaluate(input(open = 100.0, close = 100.1))
        assertEquals(0.0, result.autoThreshold)
    }

    @Test
    fun chartModeEvaluatesFvgConditionsOnEachSuppliedChartBar() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = false)
        val result = calculator.evaluate(
            input(open = 100.0, close = 106.0).copy(
                currentLow = 105.0,
                highTwoBarsAgo = 104.0
            )
        )
        assertTrue(result.bullishFvg)
    }

    @Test
    fun candleProcessorMatchesLuxAlgoHistoryOffsetsAndWarmsUpFromFirstBar() {
        val processor = LuxAlgoFvgAutoProcessor(
            chartTimeframe = AnalysisTimeframe.FIFTEEN_MINUTES,
            autoThresholdEnabled = true
        )
        val start = 1_700_000_100_000L - (1_700_000_100_000L % (15L * 60_000L))
        val candles = listOf(
            candle(100.0, 101.0, 105.0, 95.0, start),
            candle(101.0, 103.0, 108.0, 99.0, start + 15L * 60_000L),
            candle(103.0, 104.0, 110.0, 102.0, start + 30L * 60_000L)
        )

        val first = processor.process(candles[0])
        val second = processor.process(candles[1])
        val third = processor.process(candles[2])

        assertNull(first.deltaPercent)
        assertNull(first.autoThreshold)
        assertEquals(1.0, second.deltaPercent!!, 1e-9)
        assertEquals(2.0, second.autoThreshold!!, 1e-9)
        assertEquals((103.0 - 101.0) / 101.0 * 100.0, third.deltaPercent!!, 1e-9)
        assertEquals(
            (1.0 + abs((103.0 - 101.0) / 101.0 * 100.0)) / 2.0 * 2.0,
            third.autoThreshold!!,
            1e-9
        )
    }


    @Test
    fun processorRejectsMissingChartCandlesInsteadOfShiftingAutoThreshold() {
        val processor = LuxAlgoFvgAutoProcessor(AnalysisTimeframe.FIFTEEN_MINUTES)
        val start = 1_700_000_100_000L
        processor.process(candle(100.0, 101.0, 105.0, 95.0, start))

        assertFailsWith<IllegalArgumentException> {
            processor.process(candle(102.0, 103.0, 108.0, 99.0, start + 30L * 60_000L))
        }
    }

    private fun candle(open: Double, close: Double, high: Double, low: Double, openTime: Long) = MarketCandle(
        openTime = openTime,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1.0
    )

    private fun input(open: Double, close: Double) = FvgInput(
        previousOpen = open,
        previousClose = close,
        currentHigh = 110.0,
        currentLow = 105.0,
        highTwoBarsAgo = 104.0,
        lowTwoBarsAgo = 90.0
    )
}
