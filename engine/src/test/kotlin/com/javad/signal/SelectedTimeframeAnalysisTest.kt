package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SelectedTimeframeAnalysisTest {
    @Test
    fun historyLoaderAcceptsAnyNonEmptyConsecutiveAvailableHistory() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)
        val interval = AnalysisTimeframe.FIVE_MINUTES.durationMillis
        val start = 1_700_000_100_000L
        val history = (0 until 137).map { index ->
            testCandle(start + index * interval)
        }

        analysis.loadHistory("BTCUSDT.P", history)

        assertEquals(true, analysis.isHistoryLoaded("BTCUSDT.P"))
    }

    @Test
    fun historyLoaderRejectsEmptyHistory() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)

        assertFailsWith<IllegalArgumentException> {
            analysis.loadHistory("BTCUSDT.P", emptyList())
        }
        assertEquals(false, analysis.isHistoryLoaded("BTCUSDT.P"))
    }

    @Test
    fun historyLoaderRejectsGapsInAvailableHistory() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)
        val interval = AnalysisTimeframe.FIVE_MINUTES.durationMillis
        val start = 1_700_000_100_000L
        val history = (0 until 137).map { index ->
            val adjustedIndex = if (index >= 70) index + 1 else index
            testCandle(start + adjustedIndex * interval)
        }

        assertFailsWith<IllegalArgumentException> {
            analysis.loadHistory("BTCUSDT.P", history)
        }
        assertEquals(false, analysis.isHistoryLoaded("BTCUSDT.P"))
    }

    private fun testCandle(openTime: Long) = MarketCandle(
        openTime = openTime,
        open = 100.0,
        high = 101.0,
        low = 99.0,
        close = 100.5,
        volume = 10.0
    )
}
