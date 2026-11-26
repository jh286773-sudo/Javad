package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuxAlgoFvgAutoEngineTest {
    @Test
    fun autoThresholdUsesCumulativeAbsoluteDeltaAndChartBarIndex() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = true)
        val first = calculator.evaluate(input(open = 100.0, close = 100.1, newTf = true))
        val second = calculator.evaluate(input(open = 100.0, close = 100.1, newTf = true))
        val third = calculator.evaluate(input(open = 100.0, close = 100.1, newTf = true))
        val fourth = calculator.evaluate(input(open = 100.0, close = 106.0, newTf = true))

        assertNull(first.autoThreshold) // bar_index == 0, Pine result is na
        assertEquals(0.4, second.autoThreshold!!, 1e-9)
        assertEquals(0.3, third.autoThreshold!!, 1e-9)
        assertEquals(4.2, fourth.autoThreshold!!, 1e-9)
        assertTrue(fourth.bullishFvg)
    }

    @Test
    fun disabledAutoThresholdUsesZero() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = false)
        val result = calculator.evaluate(input(open = 100.0, close = 100.1, newTf = true))
        assertEquals(0.0, result.autoThreshold)
    }

    @Test
    fun noFvgCanFormWithoutNewFvgTimeframe() {
        val calculator = LuxAlgoFvgAutoCalculator(autoThresholdEnabled = false)
        val result = calculator.evaluate(input(open = 100.0, close = 106.0, newTf = false))
        assertFalse(result.bullishFvg)
        assertFalse(result.bearishFvg)
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

    private fun input(open: Double, close: Double, newTf: Boolean) = FvgInput(
        previousOpen = open,
        previousClose = close,
        currentHigh = 110.0,
        currentLow = 105.0,
        highTwoBarsAgo = 104.0,
        lowTwoBarsAgo = 90.0,
        newFvgTimeframe = newTf
    )
}
