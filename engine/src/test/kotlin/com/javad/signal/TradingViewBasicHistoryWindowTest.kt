package com.javad.signal

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradingViewBasicHistoryWindowTest {
    private fun utc(value: String): Long = Instant.parse(value).toEpochMilli()

    @Test
    fun targetCountsMatchTheUserConfirmedTimeframeCounts() {
        val expected = mapOf(
            AnalysisTimeframe.ONE_MINUTE to 7_200L,
            AnalysisTimeframe.FIVE_MINUTES to 5_472L,
            AnalysisTimeframe.FIFTEEN_MINUTES to 5_568L,
            AnalysisTimeframe.THIRTY_MINUTES to 33_408L,
            AnalysisTimeframe.ONE_HOUR to 7_944L,
            AnalysisTimeframe.TWO_HOURS to 8_352L
        )
        expected.forEach { (timeframe, count) ->
            assertEquals(count, TradingViewBasicHistoryWindow.targetCandleCount(timeframe))
        }
    }

    @Test
    fun latestClosedCandleAndRemainingSlotsDetermineTheHistoryStart() {
        val serverTime = utc("2026-11-27T18:33:00Z")
        val expected = mapOf(
            AnalysisTimeframe.ONE_MINUTE to Triple(327L, 6_873L, "2026-11-23T00:00:00Z"),
            AnalysisTimeframe.FIVE_MINUTES to Triple(66L, 5_406L, "2026-11-09T00:00:00Z"),
            AnalysisTimeframe.FIFTEEN_MINUTES to Triple(22L, 5_546L, "2026-10-01T00:00:00Z"),
            AnalysisTimeframe.THIRTY_MINUTES to Triple(11L, 33_397L, "2025-01-01T00:00:00Z"),
            AnalysisTimeframe.ONE_HOUR to Triple(6L, 7_938L, "2026-01-01T00:00:00Z"),
            AnalysisTimeframe.TWO_HOURS to Triple(3L, 8_349L, "2025-01-01T00:00:00Z")
        )

        expected.forEach { (timeframe, values) ->
            assertEquals(
                values.first,
                TradingViewBasicHistoryWindow.remainingCandleCountUntilNextUtcMidnight(
                    timeframe,
                    serverTime
                ),
                timeframe.apiInterval
            )
            assertEquals(
                values.second,
                TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, serverTime),
                timeframe.apiInterval
            )
            assertEquals(
                utc(values.third),
                TradingViewBasicHistoryWindow.startTimeMillis(timeframe, serverTime),
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun startupAtSixPmSubtractsRemainingFiveMinuteSlotsAndAnchorsAtUtcMidnight() {
        // At 18:03 UTC the 18:00 candle is forming, so 72 five-minute
        // slots remain from its open through (but excluding) next midnight.
        val serverTime = utc("2026-11-27T18:03:00Z")
        val timeframe = AnalysisTimeframe.FIVE_MINUTES

        assertEquals(
            72L,
            TradingViewBasicHistoryWindow.remainingCandleCountUntilNextUtcMidnight(
                timeframe,
                serverTime
            )
        )
        assertEquals(
            5_400L,
            TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, serverTime)
        )
        assertEquals(
            utc("2026-11-09T00:00:00Z"),
            TradingViewBasicHistoryWindow.startTimeMillis(timeframe, serverTime)
        )
    }

    @Test
    fun utcMidnightMovesTheLeftEdgeByExactlyOneDayAndExcludesTheNewFormingCandle() {
        val midnight = utc("2026-11-28T00:00:00Z")
        val expected = mapOf(
            AnalysisTimeframe.ONE_MINUTE to Triple(1_440L, 5_760L, "2026-11-24T00:00:00Z"),
            AnalysisTimeframe.FIVE_MINUTES to Triple(288L, 5_184L, "2026-11-10T00:00:00Z"),
            AnalysisTimeframe.FIFTEEN_MINUTES to Triple(96L, 5_472L, "2026-10-02T00:00:00Z"),
            AnalysisTimeframe.THIRTY_MINUTES to Triple(48L, 33_360L, "2025-01-02T00:00:00Z"),
            AnalysisTimeframe.ONE_HOUR to Triple(24L, 7_920L, "2026-01-02T00:00:00Z"),
            AnalysisTimeframe.TWO_HOURS to Triple(12L, 8_340L, "2025-01-02T00:00:00Z")
        )

        expected.forEach { (timeframe, values) ->
            assertEquals(
                values.first,
                TradingViewBasicHistoryWindow.remainingCandleCountUntilNextUtcMidnight(
                    timeframe,
                    midnight
                ),
                timeframe.apiInterval
            )
            assertEquals(
                values.second,
                TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, midnight),
                timeframe.apiInterval
            )
            assertEquals(
                utc(values.third),
                TradingViewBasicHistoryWindow.startTimeMillis(timeframe, midnight),
                timeframe.apiInterval
            )
            assertEquals(
                TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(timeframe, midnight),
                TradingViewBasicHistoryWindow.endTimeMillis(timeframe, midnight),
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun restHistoryExcludesTheCurrentCandleAndLiveCountAddsItAsPreview() {
        val serverTime = utc("2026-11-27T18:33:00Z")
        AnalysisTimeframe.entries.forEach { timeframe ->
            val currentOpen = TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(
                timeframe,
                serverTime
            )
            assertEquals(
                TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, serverTime) + 1L,
                TradingViewBasicHistoryWindow.expectedLiveCandleCount(timeframe, serverTime),
                timeframe.apiInterval
            )
            assertEquals(currentOpen, TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime))
            assertTrue(currentOpen <= serverTime)
            assertTrue(serverTime - currentOpen < timeframe.durationMillis)
        }
    }

    @Test
    fun everyCalculatedHistoryStartIsAlignedAndEndsAtTheLatestClosedCandle() {
        val serverTime = utc("2026-11-27T18:33:00Z")
        AnalysisTimeframe.entries.forEach { timeframe ->
            val start = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, serverTime)
            val currentOpen = TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(
                timeframe,
                serverTime
            )
            val latestClosedOpen = currentOpen - timeframe.durationMillis
            val expectedCount = TradingViewBasicHistoryWindow.expectedCompletedCandleCount(
                timeframe,
                serverTime
            )
            assertEquals(0L, start % timeframe.durationMillis, timeframe.apiInterval)
            assertEquals(
                expectedCount,
                (latestClosedOpen - start) / timeframe.durationMillis + 1L,
                timeframe.apiInterval
            )
        }
    }
}
