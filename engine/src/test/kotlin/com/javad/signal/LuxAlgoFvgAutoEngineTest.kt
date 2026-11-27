package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

class LuxAlgoFvgAutoEngineTest {
    @Test
    fun heikinAshiCandlesDriveBothFvgAndAutoThreshold() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_000_000L - (1_700_000_000_000L % interval)
        val processor = LuxAlgoFvgAutoProcessor(
            chartTimeframe = timeframe,
            useHeikinAshi = true
        )

        // The first two standard candles produce flat HA candles at 100.
        processor.process(candle(100.0, 100.0, 101.0, 99.0, start))
        processor.process(candle(100.0, 100.0, 101.0, 99.0, start + interval))
        // HA bar 2: O=100, C=105.25, H=111, L=100.
        processor.process(candle(100.0, 110.0, 111.0, 100.0, start + 2L * interval))
        // HA bar 3: O=102.625, C=115.25, H=121, L=102.625.
        val result = processor.process(
            candle(110.0, 120.0, 121.0, 110.0, start + 3L * interval)
        )

        assertEquals(5.25, result.deltaPercent!!, 1e-9)
        assertEquals(3.5, result.autoThreshold!!, 1e-9)
        assertTrue(result.bullishFvg)
        val zone = result.fvgState!!.formedZones.single()
        assertEquals(102.625, zone.maxBox.top, 1e-9)
        assertEquals(101.8125, zone.maxBox.bottom, 1e-9)
        assertEquals(101.8125, zone.minBox.top, 1e-9)
        assertEquals(101.0, zone.minBox.bottom, 1e-9)
    }

    @Test
    fun defaultProcessorBuildsAndRetainsTheEntireHeikinAshiChainFromFirstToLastCandle() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_000_000L - (1_700_000_000_000L % interval)
        val processor = LuxAlgoFvgAutoProcessor(timeframe)
        val raw = listOf(
            candle(100.0, 102.0, 104.0, 99.0, start),
            candle(102.0, 104.0, 106.0, 101.0, start + interval),
            candle(104.0, 103.0, 108.0, 100.0, start + 2L * interval),
            candle(103.0, 107.0, 110.0, 102.0, start + 3L * interval)
        )

        val first = processor.process(raw[0]).analysisCandle!!
        val second = processor.process(raw[1]).analysisCandle!!
        val third = processor.process(raw[2]).analysisCandle!!
        assertEquals((100.0 + 102.0) / 2.0, first.open, 1e-12)
        assertEquals((100.0 + 104.0 + 99.0 + 102.0) / 4.0, first.close, 1e-12)
        assertEquals((first.open + first.close) / 2.0, second.open, 1e-12)
        assertEquals((102.0 + 106.0 + 101.0 + 104.0) / 4.0, second.close, 1e-12)
        assertEquals((second.open + second.close) / 2.0, third.open, 1e-12)

        val preview = processor.preview(raw[3]).analysisCandle!!
        assertEquals(3, processor.committedHeikinAshiHistory().size)
        assertEquals((third.open + third.close) / 2.0, preview.open, 1e-12)

        val fourth = processor.process(raw[3]).analysisCandle!!
        val complete = processor.committedHeikinAshiHistory()
        assertEquals(4, complete.size)
        assertEquals(listOf(first, second, third, fourth), complete)
        assertEquals((third.open + third.close) / 2.0, fourth.open, 1e-12)
        assertEquals((103.0 + 110.0 + 102.0 + 107.0) / 4.0, fourth.close, 1e-12)
    }

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
    fun calculatorResumesAutoThresholdWithPriorCumulativeValueAndPineIndex() {
        val calculator = LuxAlgoFvgAutoCalculator(
            autoThresholdEnabled = true,
            firstChartBarIndex = 500L,
            initialCumulativeAbsoluteDelta = 25.0
        )

        val resumed = calculator.evaluate(input(open = 100.0, close = 101.0))
        assertEquals(500L, resumed.chartBarIndex)
        assertEquals(26.0 / 500.0 * 2.0, resumed.autoThreshold!!, 1e-12)

        val following = calculator.evaluate(input(open = 100.0, close = 102.0))
        assertEquals(501L, following.chartBarIndex)
        assertEquals(28.0 / 501.0 * 2.0, following.autoThreshold!!, 1e-12)
    }

    @Test
    fun threeThousandFiveHundredCandlesMatchAcrossSinglePassAndPageBoundaries() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_000_000L - (1_700_000_000_000L % interval)
        var previousClose = 100.0
        val candles = (0 until 3_500).map { index ->
            val open = previousClose
            val changePercent = ((index * 37 % 101) - 50) * 0.015
            val close = open * (1.0 + changePercent / 100.0)
            val high = maxOf(open, close) * (1.0 + (0.20 + (index % 7) * 0.03) / 100.0)
            val low = minOf(open, close) * (1.0 - (0.20 + (index % 5) * 0.04) / 100.0)
            previousClose = close
            MarketCandle(
                openTime = start + index * interval,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 10.0 + index
            )
        }

        val singlePass = LuxAlgoFvgAutoProcessor(timeframe, useHeikinAshi = false).processHistory(candles)
        val pagedProcessor = LuxAlgoFvgAutoProcessor(timeframe, useHeikinAshi = false)
        val pageSizes = mutableListOf<Int>()
        val pagedPass = candles.chunked(1_000).flatMap { page ->
            pageSizes += page.size
            page.map(pagedProcessor::process)
        }

        assertEquals(listOf(1_000, 1_000, 1_000, 500), pageSizes)
        assertEquals(3_500, singlePass.size)
        assertEquals(singlePass, pagedPass)
        assertEquals(0L, singlePass.first().chartBarIndex)
        assertEquals(3_499L, singlePass.last().chartBarIndex)

        var cumulativeAbsoluteDelta = 0.0
        candles.forEachIndexed { index, candle ->
            val evaluation = singlePass[index]
            val previous = candles.getOrNull(index - 1)
            val twoBarsAgo = candles.getOrNull(index - 2)
            val delta = previous?.let {
                (it.close - it.open) / it.open * 100.0
            }
            if (delta != null) cumulativeAbsoluteDelta += abs(delta)
            val expectedThreshold = if (index == 0) null
                else cumulativeAbsoluteDelta / index.toDouble() * 2.0
            if (expectedThreshold == null) {
                assertNull(evaluation.autoThreshold)
            } else {
                assertEquals(expectedThreshold, evaluation.autoThreshold!!, 1e-10)
            }

            val threshold = expectedThreshold
            val expectedBullish = delta != null && threshold != null && twoBarsAgo != null &&
                candle.low > twoBarsAgo.high &&
                previous!!.close > twoBarsAgo.high &&
                delta > threshold
            val expectedBearish = delta != null && threshold != null && twoBarsAgo != null &&
                candle.high < twoBarsAgo.low &&
                previous!!.close < twoBarsAgo.low &&
                -delta > threshold
            assertEquals(expectedBullish, evaluation.bullishFvg, "Bullish FVG mismatch at bar $index")
            assertEquals(expectedBearish, evaluation.bearishFvg, "Bearish FVG mismatch at bar $index")
        }
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
            autoThresholdEnabled = true,
            useHeikinAshi = false
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
        val processor = LuxAlgoFvgAutoProcessor(AnalysisTimeframe.FIFTEEN_MINUTES, useHeikinAshi = false)
        val start = 1_700_000_100_000L
        processor.process(candle(100.0, 101.0, 105.0, 95.0, start))

        assertFailsWith<IllegalArgumentException> {
            processor.process(candle(102.0, 103.0, 108.0, 99.0, start + 30L * 60_000L))
        }
    }

    @Test
    fun bullishFvgBoxesMatchLuxAlgoBoundariesAndStrictLowInvalidation() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_000_000L - (1_700_000_000_000L % interval)
        val processor = LuxAlgoFvgAutoProcessor(timeframe, useHeikinAshi = false)

        processor.process(candle(100.0, 100.1, 100.2, 99.9, start))
        processor.process(candle(100.1, 100.2, 100.3, 100.0, start + interval))
        processor.process(candle(100.2, 102.204, 102.3, 100.1, start + 2L * interval))
        val formedEvaluation = processor.process(
            candle(102.204, 102.3, 102.5, 100.5, start + 3L * interval)
        )
        val zone = formedEvaluation.fvgState!!.formedZones.single()

        assertTrue(formedEvaluation.bullishFvg)
        assertEquals(FvgDirection.LONG, zone.direction)
        assertEquals(3L, zone.chartBarIndex)
        assertEquals(2L, zone.leftBarIndex)
        assertEquals(4L, zone.rightBarIndex)
        assertEquals(FvgBox(top = 100.5, bottom = 100.4), zone.maxBox)
        assertEquals(FvgBox(top = 100.4, bottom = 100.3), zone.minBox)

        val exactTouch = processor.process(
            candle(102.3, 102.3, 102.6, 100.3, start + 4L * interval)
        )
        assertTrue(exactTouch.fvgState!!.removedZones.isEmpty())
        assertEquals(listOf(zone), exactTouch.fvgState.activeZones)

        val strictBreak = processor.process(
            candle(102.3, 102.2, 102.5, 100.29, start + 5L * interval)
        )
        assertEquals(listOf(zone), strictBreak.fvgState!!.removedZones)
        assertTrue(strictBreak.fvgState.activeZones.isEmpty())
    }

    @Test
    fun bearishFvgBoxesMatchLuxAlgoBoundariesAndStrictHighInvalidation() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_000_000L - (1_700_000_000_000L % interval)
        val processor = LuxAlgoFvgAutoProcessor(timeframe, useHeikinAshi = false)

        processor.process(candle(100.0, 99.9, 100.1, 99.8, start))
        processor.process(candle(99.9, 99.8, 100.0, 99.7, start + interval))
        processor.process(candle(99.8, 97.804, 99.9, 97.7, start + 2L * interval))
        val formedEvaluation = processor.process(
            candle(97.804, 97.7, 99.5, 97.5, start + 3L * interval)
        )
        val zone = formedEvaluation.fvgState!!.formedZones.single()

        assertTrue(formedEvaluation.bearishFvg)
        assertEquals(FvgDirection.SHORT, zone.direction)
        assertEquals(3L, zone.chartBarIndex)
        assertEquals(2L, zone.leftBarIndex)
        assertEquals(4L, zone.rightBarIndex)
        assertEquals(FvgBox(top = 99.5, bottom = 99.6), zone.maxBox)
        assertEquals(FvgBox(top = 99.6, bottom = 99.7), zone.minBox)

        val exactTouch = processor.process(
            candle(97.7, 97.7, 99.5, 97.4, start + 4L * interval)
        )
        assertTrue(exactTouch.fvgState!!.removedZones.isEmpty())
        assertEquals(listOf(zone), exactTouch.fvgState.activeZones)

        val strictBreak = processor.process(
            candle(97.7, 97.8, 99.51, 97.4, start + 5L * interval)
        )
        assertEquals(listOf(zone), strictBreak.fvgState!!.removedZones)
        assertTrue(strictBreak.fvgState.activeZones.isEmpty())
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
