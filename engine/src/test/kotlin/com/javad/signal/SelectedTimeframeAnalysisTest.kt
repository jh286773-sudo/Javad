package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectedTimeframeAnalysisTest {
    @Test
    fun selectedTimeframeIsAppliedAndQualifyingLiveCandleCanEmitIntrabar() {
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

            // LuxAlgo evaluates the forming third candle intrabar. Once its
            // live high/low satisfy FVG + Auto, LONG can be delivered at once.
            val liveResult = analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, liveCandle, isClosed = false, timeframe = timeframe)
            )
            assertEquals(timeframe, liveResult?.timeframe)
            assertEquals(FvgDirection.LONG, liveResult?.signal?.direction)
            assertEquals(liveCandle.openTime, liveResult?.candle?.openTime)

            // Further ticks and the eventual close must not duplicate it.
            assertNull(
                analysis.onCandleUpdate(
                    ToobitCandleUpdate(symbol, liveCandle, isClosed = false, timeframe = timeframe)
                )
            )
            assertNull(
                analysis.onCandleUpdate(
                    ToobitCandleUpdate(symbol, liveCandle, isClosed = true, timeframe = timeframe)
                )
            )
        }
    }

    @Test
    fun repeatedSameDirectionIsSuppressedUntilAnOppositeSignalAppears() {
        val symbol = "BTCUSDT.P"
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = alignedStart(timeframe)
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(timeframe, setOf(symbol))
        )
        analysis.loadHistory(
            symbol,
            listOf(
                candle(100.0, 100.0, 105.0, 95.0, start),
                candle(200.0, 200.0, 210.0, 190.0, start + interval),
                candle(200.0, 215.0, 220.0, 195.0, start + 2L * interval)
            )
        )

        val firstLong = candle(230.0, 299.0, 300.0, 230.0, start + 3L * interval)
        assertEquals(
            FvgDirection.LONG,
            analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, firstLong, isClosed = true, timeframe = timeframe)
            )?.signal?.direction
        )

        // This candle also satisfies the bullish source formula, but the
        // same-side level is not delivered again.
        val repeatedLong = candle(500.0, 226.0, 500.0, 225.0, start + 4L * interval)
        assertNull(
            analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, repeatedLong, isClosed = true, timeframe = timeframe)
            )
        )

        // The next candle satisfies the bearish source formula and re-arms
        // delivery by changing the last emitted direction.
        val oppositeShort = candle(220.0, 210.0, 220.0, 205.0, start + 5L * interval)
        assertEquals(
            FvgDirection.SHORT,
            analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, oppositeShort, isClosed = true, timeframe = timeframe)
            )?.signal?.direction
        )
    }

    @Test
    fun sameDirectionRemainsSuppressedAfterHistoryRecovery() {
        val symbol = "BTCUSDT.P"
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val start = alignedStart(timeframe)
        val analysis = SelectedTimeframeAnalysis(
            AnalysisSettings(timeframe, setOf(symbol))
        )
        val history = listOf(
            candle(100.0, 100.0, 105.0, 95.0, start),
            candle(200.0, 200.0, 210.0, 190.0, start + interval),
            candle(200.0, 215.0, 220.0, 195.0, start + 2L * interval)
        )
        analysis.loadHistory(symbol, history)

        val firstLong = candle(230.0, 299.0, 300.0, 230.0, start + 3L * interval)
        assertEquals(
            FvgDirection.LONG,
            analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, firstLong, isClosed = true, timeframe = timeframe)
            )?.signal?.direction
        )

        // A stream recovery reloads candles but must not re-arm a same-side alert.
        analysis.loadHistory(
            symbol,
            history + firstLong,
            preserveSignalDirection = true
        )
        val repeatedLong = candle(500.0, 226.0, 500.0, 225.0, start + 4L * interval)
        assertNull(
            analysis.onCandleUpdate(
                ToobitCandleUpdate(symbol, repeatedLong, isClosed = true, timeframe = timeframe)
            )
        )
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
