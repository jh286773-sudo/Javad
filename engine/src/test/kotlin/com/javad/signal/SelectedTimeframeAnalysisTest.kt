package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectedTimeframeAnalysisTest {
    @Test
    fun selectedTimeframeIsAppliedToEverySupportedIntervalAndOnlyClosedCandlesAreEvaluated() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val symbol = "BTCUSDT.P"
            val settings = AnalysisSettings(
                selectedTimeframe = timeframe,
                symbols = setOf(symbol)
            )
            val analysis = SelectedTimeframeAnalysis(settings)
            val start = alignedStart(timeframe)
            val history = seedHistory(start, timeframe.durationMillis)

            analysis.loadHistory(symbol, history)
            assertTrue(analysis.isHistoryLoaded(symbol))

            val liveCandle = candle(
                open = 120.0,
                close = 121.0,
                high = 130.0,
                low = 110.0,
                openTime = start + 3L * timeframe.durationMillis
            )

            assertNull(
                analysis.onCandleUpdate(
                    ToobitCandleUpdate(symbol, liveCandle, isClosed = false, timeframe = timeframe)
                ),
                "An open ${timeframe.apiInterval} candle must not be analyzed"
            )

            val result = analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, liveCandle, isClosed = true, timeframe = timeframe)
            )

            assertEquals(timeframe, result?.timeframe)
            assertEquals(FvgDirection.LONG, result?.signal?.direction)
            assertEquals(liveCandle.openTime, result?.candle?.openTime)
        }
    }

    @Test
    fun timeframeChangeClearsOldHistoryAndRejectsOldStreamInterval() {
        val symbol = "BTCUSDT.P"
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(AnalysisTimeframe.FIVE_MINUTES, setOf(symbol))
        )
        val fiveMinuteStart = alignedStart(AnalysisTimeframe.FIVE_MINUTES)
        analysis.loadHistory(
            symbol,
            seedHistory(fiveMinuteStart, AnalysisTimeframe.FIVE_MINUTES.durationMillis)
        )

        analysis.updateSettings(
            AnalysisSettings(AnalysisTimeframe.ONE_HOUR, setOf(symbol))
        )

        assertFalse(analysis.isHistoryLoaded(symbol))
        assertFailsWith<IllegalStateException> {
            analysis.onCandleUpdate(
                ToobitCandleUpdate(
                    symbol,
                    candle(120.0, 121.0, 130.0, 110.0, fiveMinuteStart + 15L * 60_000L),
                    isClosed = true,
                    timeframe = AnalysisTimeframe.FIVE_MINUTES
                )
            )
        }
    }

    @Test
    fun duplicateClosedCandleDoesNotEmitDuplicateSignal() {
        val symbol = "BTCUSDT.P"
        val timeframe = AnalysisTimeframe.FIFTEEN_MINUTES
        val start = alignedStart(timeframe)
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(timeframe, setOf(symbol))
        )
        analysis.loadHistory(symbol, seedHistory(start, timeframe.durationMillis))
        val live = candle(120.0, 121.0, 130.0, 110.0, start + 3L * timeframe.durationMillis)
        val update = ToobitCandleUpdate(symbol, live, true, timeframe = timeframe)

        assertEquals(FvgDirection.LONG, analysis.onCandleUpdate(update)?.signal?.direction)
        assertNull(analysis.onCandleUpdate(update))
    }

    @Test
    fun gapInvalidatesHistoryUntilBackfillIsLoaded() {
        val symbol = "BTCUSDT.P"
        val timeframe = AnalysisTimeframe.ONE_MINUTE
        val start = alignedStart(timeframe)
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(timeframe, setOf(symbol))
        )
        analysis.loadHistory(symbol, seedHistory(start, timeframe.durationMillis))
        val gapCandle = candle(
            120.0, 121.0, 130.0, 110.0,
            start + 4L * timeframe.durationMillis
        )

        assertFailsWith<IllegalStateException> {
            analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, gapCandle, true, gapDetected = true, timeframe = timeframe)
            )
        }
        assertFalse(analysis.isHistoryLoaded(symbol))
    }

    private fun seedHistory(start: Long, interval: Long) = listOf(
        candle(100.0, 101.0, 105.0, 95.0, start),
        candle(100.0, 101.0, 104.0, 96.0, start + interval),
        candle(100.0, 120.0, 125.0, 99.0, start + 2L * interval)
    )

    private fun alignedStart(timeframe: AnalysisTimeframe): Long {
        val now = 1_700_000_100_000L
        return now - now % timeframe.durationMillis
    }

    private fun candle(
        open: Double,
        close: Double,
        high: Double,
        low: Double,
        openTime: Long
    ) = MarketCandle(openTime, open, high, low, close, 1.0)
}
