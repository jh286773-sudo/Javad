package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val processor = LuxAlgoFvgAutoProcessor(autoThresholdEnabled = true)
        val candles = listOf(
            candle(100.0, 101.0, 105.0, 95.0),
            candle(101.0, 103.0, 108.0, 99.0),
            candle(103.0, 104.0, 110.0, 102.0)
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
    fun signalGateSuppressesRepeatedDirectionAndAllowsOppositeChange() {
        val gate = DirectionSignalGate()
        val long = FvgEvaluation(10, 6.0, 4.0, true, false)
        val repeatedLong = FvgEvaluation(11, 7.0, 4.0, true, false)
        val short = FvgEvaluation(12, -6.0, 4.0, false, true)

        assertNotNull(gate.accept(long))
        assertNull(gate.accept(repeatedLong))
        assertEquals(FvgDirection.SHORT, gate.accept(short)?.direction)
    }

    private fun candle(open: Double, close: Double, high: Double, low: Double) = MarketCandle(
        openTime = 0L,
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
