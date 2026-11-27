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
        assertEquals(history.first().openTime to history.last().openTime, analysis.historyRange("BTCUSDT.P"))
    }

    @Test
    fun historyMatchesChecksEveryCandleNotOnlyTheRange() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)
        val interval = AnalysisTimeframe.FIVE_MINUTES.durationMillis
        val start = 1_700_000_100_000L
        val history = (0 until 5).map { index -> testCandle(start + index * interval) }
        analysis.loadHistory("BTCUSDT.P", history)

        assertEquals(true, analysis.historyMatches("BTCUSDT.P", history))

        val changedMiddleCandle = history.toMutableList().also {
            it[2] = it[2].copy(close = 100.75)
        }
        assertEquals(false, analysis.historyMatches("BTCUSDT.P", changedMiddleCandle))
    }

    @Test
    fun selectedAnalysisRetainsFullHeikinAshiHistoryAndKeepsRawSnapshotSeparate() {
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = 1_700_000_100_000L - (1_700_000_100_000L % interval)
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(selectedTimeframe = timeframe, symbols = setOf("BTCUSDT.P"))
        )
        val raw = listOf(
            MarketCandle(start, 100.0, 104.0, 99.0, 102.0, 10.0),
            MarketCandle(start + interval, 102.0, 106.0, 101.0, 104.0, 11.0),
            MarketCandle(start + 2L * interval, 104.0, 108.0, 100.0, 103.0, 12.0)
        )

        analysis.loadHistory("BTCUSDT.P", raw)
        val ha = analysis.heikinAshiHistory("BTCUSDT.P")
        assertEquals(3, ha.size)
        assertEquals((100.0 + 102.0) / 2.0, ha[0].open, 1e-12)
        assertEquals((100.0 + 104.0 + 99.0 + 102.0) / 4.0, ha[0].close, 1e-12)
        assertEquals((ha[0].open + ha[0].close) / 2.0, ha[1].open, 1e-12)
        assertEquals((ha[1].open + ha[1].close) / 2.0, ha[2].open, 1e-12)

        val liveRaw = MarketCandle(start + 3L * interval, 103.0, 110.0, 102.0, 107.0, 13.0)
        val preview = analysis.onCandleUpdate(
            ToobitCandleUpdate("BTCUSDT.P", liveRaw, false, false, timeframe)
        )
        assertEquals(3, analysis.heikinAshiHistory("BTCUSDT.P").size)
        if (preview != null) {
            assertEquals((ha.last().open + ha.last().close) / 2.0, preview.candle.open, 1e-12)
        }

        analysis.onCandleUpdate(
            ToobitCandleUpdate("BTCUSDT.P", liveRaw, true, false, timeframe)
        )
        assertEquals(4, analysis.heikinAshiHistory("BTCUSDT.P").size)
        assertEquals(raw + liveRaw, listOf(raw[0], raw[1], raw[2], liveRaw))
        assertEquals(true, analysis.historyMatches("BTCUSDT.P", raw + liveRaw))
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
        assertEquals(null, analysis.historyRange("BTCUSDT.P"))
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
        val startTime = 1_700_000_000_000L - (1_700_000_000_000L % interval)

        fun bar(index: Int, open: Double, close: Double, high: Double, low: Double) =
            MarketCandle(
                openTime = startTime + index * interval,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 10.0
            )

        fun settings() = AnalysisSettings(
            selectedTimeframe = timeframe,
            symbols = setOf("BTCUSDT.P")
        )

        fun update(candle: MarketCandle, closed: Boolean = false) = ToobitCandleUpdate(
            javadSymbol = "BTCUSDT.P",
            candle = candle,
            isClosed = closed,
            gapDetected = false,
            timeframe = timeframe
        )

        val longAnalysis = SelectedTimeframeAnalysis(settings())
        val longHistory = listOf(
            bar(0, 100.0, 100.1, 100.2, 99.9),
            bar(1, 100.1, 100.2, 100.3, 100.0),
            bar(2, 100.2, 102.204, 102.3, 100.1)
        )
        longAnalysis.loadHistory("BTCUSDT.P", longHistory)
        val longCandle = bar(3, 102.204, 102.3, 102.5, 100.5)

        // The HA-derived FVG + Auto condition is true on this forming candle.
        val firstLongSignal = longAnalysis.onCandleUpdate(update(longCandle))!!
        assertEquals(FvgDirection.LONG, firstLongSignal.signal.direction)
        val lastCommittedHa = longAnalysis.heikinAshiHistory("BTCUSDT.P").last()
        assertEquals((lastCommittedHa.open + lastCommittedHa.close) / 2.0, firstLongSignal.candle.open, 1e-12)
        assertEquals(
            (longCandle.open + longCandle.high + longCandle.low + longCandle.close) / 4.0,
            firstLongSignal.candle.close,
            1e-12
        )
        repeat(2) {
            assertEquals(FvgDirection.LONG, longAnalysis.onCandleUpdate(update(longCandle))?.signal?.direction)
        }
        assertEquals(FvgDirection.LONG, longAnalysis.onCandleUpdate(update(longCandle, closed = true))?.signal?.direction)
        assertEquals(4, longAnalysis.heikinAshiHistory("BTCUSDT.P").size)

        val shortAnalysis = SelectedTimeframeAnalysis(settings())
        val shortHistory = listOf(
            bar(0, 200.0, 200.0, 201.0, 199.0),
            bar(1, 200.0, 200.0, 201.0, 199.0),
            bar(2, 200.0, 100.0, 201.0, 99.0)
        )
        shortAnalysis.loadHistory("BTCUSDT.P", shortHistory)
        val shortCandle = bar(3, 100.0, 80.0, 101.0, 79.0)
        assertEquals(FvgDirection.SHORT, shortAnalysis.onCandleUpdate(update(shortCandle))?.signal?.direction)
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
