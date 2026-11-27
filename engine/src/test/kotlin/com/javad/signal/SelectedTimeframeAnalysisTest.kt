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

    @Test
    fun liveSignalsAllowRepeatedSameDirectionAndOppositeDirectionSignals() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_000_000L - (1_700_000_000_000L % interval)
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(
                selectedTimeframe = timeframe,
                symbols = setOf("BTCUSDT.P")
            )
        )

        fun bar(index: Int, open: Double, close: Double, high: Double, low: Double) =
            MarketCandle(
                openTime = start + index * interval,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 10.0
            )

        fun update(candle: MarketCandle, closed: Boolean = false) = ToobitCandleUpdate(
            javadSymbol = "BTCUSDT.P",
            candle = candle,
            isClosed = closed,
            gapDetected = false,
            timeframe = timeframe
        )

        val history = listOf(
            bar(0, 100.0, 100.1, 100.2, 99.9),
            bar(1, 100.1, 100.2, 100.3, 100.0),
            bar(2, 100.2, 102.204, 102.3, 100.1)
        )
        analysis.loadHistory("BTCUSDT.P", history)

        val firstLong = bar(3, 102.204, 102.3, 102.5, 100.5)
        assertEquals(
            FvgDirection.LONG,
            analysis.onCandleUpdate(update(firstLong))?.signal?.direction
        )
        // A REST backfill after reconnect must not repeat an alert already
        // emitted for this still-forming candle.
        analysis.loadHistory("BTCUSDT.P", history, preserveLastSignalCandle = true)
        assertEquals(null, analysis.onCandleUpdate(update(firstLong)))
        assertEquals(null, analysis.onCandleUpdate(update(firstLong, closed = true)))

        val nextBars = listOf(
            bar(4, 102.3, 102.4, 102.6, 102.0),
            bar(5, 102.4, 104.448, 104.6, 102.3),
            bar(6, 104.448, 104.5, 104.8, 103.0),
            bar(7, 104.5, 104.6, 104.8, 104.2),
            bar(8, 104.6, 102.508, 104.7, 102.4)
        )
        nextBars.forEachIndexed { index, candle ->
            val signal = analysis.onCandleUpdate(update(candle))
            if (index == 2) {
                assertEquals(FvgDirection.LONG, signal?.signal?.direction)
            } else {
                assertEquals(null, signal)
            }
            assertEquals(null, analysis.onCandleUpdate(update(candle, closed = true)))
        }

        val oppositeShort = bar(9, 102.508, 102.4, 104.0, 102.0)
        assertEquals(
            FvgDirection.SHORT,
            analysis.onCandleUpdate(update(oppositeShort))?.signal?.direction
        )
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
