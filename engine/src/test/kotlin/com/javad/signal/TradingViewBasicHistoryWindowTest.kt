package com.javad.signal

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradingViewBasicHistoryWindowTest {
    private val reference = Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()
    private val oneDay = 24L * 60L * 60L * 1000L

    @Test
    fun endBoundaryIsRoundedDownToSelectedTimeframe() {
        val serverTime = Instant.parse("2026-11-27T12:34:56.789Z").toEpochMilli()
        AnalysisTimeframe.entries.forEach { timeframe ->
            val end = TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime)
            assertEquals(0L, end % timeframe.durationMillis)
            assertEquals(serverTime - serverTime % timeframe.durationMillis, end)
        }
    }

    @Test
    fun endBoundaryDoesNotIncludeTheCurrentlyFormingCandle() {
        val timeframe = AnalysisTimeframe.FIFTEEN_MINUTES
        val serverTime = Instant.parse("2026-11-27T12:34:56Z").toEpochMilli()
        val end = TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime)
        assertEquals(0L, end % timeframe.durationMillis)
        assertTrue(end <= serverTime)
        assertTrue(serverTime - end < timeframe.durationMillis)
    }

    @Test
    fun observedFirstCandleAnchorsMatchTheUsersTradingViewSnapshot() {
        val expected = mapOf(
            AnalysisTimeframe.ONE_MINUTE to "2026-11-23T00:00:00Z",
            AnalysisTimeframe.FIVE_MINUTES to "2026-11-09T00:00:00Z",
            AnalysisTimeframe.FIFTEEN_MINUTES to "2026-10-01T00:00:00Z",
            AnalysisTimeframe.THIRTY_MINUTES to "2025-01-01T00:00:00Z",
            AnalysisTimeframe.ONE_HOUR to "2025-01-01T00:00:00Z",
            AnalysisTimeframe.TWO_HOURS to "2025-01-01T00:00:00Z",
            AnalysisTimeframe.FOUR_HOURS to "2024-01-01T00:00:00Z"
        )
        expected.forEach { (timeframe, isoTime) ->
            assertEquals(
                Instant.parse(isoTime).toEpochMilli(),
                TradingViewBasicHistoryWindow.startTimeMillis(timeframe, reference),
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun firstBoundaryAdvancesByExactlyOneUtcDayAndDropsOneDaysBars() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val first = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, reference)
            val nextDay = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, reference + oneDay)
            assertEquals(oneDay, nextDay - first, timeframe.apiInterval)
            assertEquals(
                oneDay / timeframe.durationMillis,
                (nextDay - first) / timeframe.durationMillis,
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun referenceSnapshotHasExpectedWindowLengths() {
        val expectedCounts = mapOf(
            AnalysisTimeframe.ONE_MINUTE to 5_760L,
            AnalysisTimeframe.FIVE_MINUTES to 5_184L,
            AnalysisTimeframe.FIFTEEN_MINUTES to 5_472L,
            AnalysisTimeframe.THIRTY_MINUTES to 33_360L,
            AnalysisTimeframe.ONE_HOUR to 16_680L,
            AnalysisTimeframe.TWO_HOURS to 8_340L,
            AnalysisTimeframe.FOUR_HOURS to 6_366L
        )
        expectedCounts.forEach { (timeframe, expected) ->
            assertEquals(
                expected,
                TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, reference),
                timeframe.apiInterval
            )
        }
    }
}
