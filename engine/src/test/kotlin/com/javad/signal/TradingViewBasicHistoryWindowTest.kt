package com.javad.signal

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradingViewBasicHistoryWindowTest {
    private val reference = Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()
    private val oneDay = 24L * 60L * 60L * 1000L

    @Test
    fun latestCandleTracksMarketTimeWithinTheDay() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val morning = Instant.parse("2026-11-27T08:17:43Z").toEpochMilli()
            val later = Instant.parse("2026-11-27T13:42:19Z").toEpochMilli()
            val firstStart = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, morning)
            val laterStart = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, later)
            val firstLatest = TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(timeframe, morning)
            val laterLatest = TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(timeframe, later)

            assertEquals(firstStart, laterStart, timeframe.apiInterval)
            assertTrue(laterLatest > firstLatest, timeframe.apiInterval)
            assertEquals(0L, laterLatest % timeframe.durationMillis, timeframe.apiInterval)
        }
    }

    @Test
    fun latestCandleMovesByOneBarWhenTheNextBarBegins() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val first = Instant.parse("2026-11-27T12:00:00Z").toEpochMilli()
            val next = first + timeframe.durationMillis
            assertEquals(
                timeframe.durationMillis,
                TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(timeframe, next) -
                    TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(timeframe, first),
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun restEndIsExclusiveAtTheCurrentCandleOpenTime() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val serverTime = Instant.parse("2026-11-27T12:34:56.789Z").toEpochMilli()
            val latest = TradingViewBasicHistoryWindow.latestCandleOpenTimeMillis(timeframe, serverTime)
            assertEquals(latest, TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime))
            assertTrue(latest <= serverTime)
            assertTrue(serverTime - latest < timeframe.durationMillis)
        }
    }

    @Test
    fun observedFirstCandleAnchorsMatchTheUsersTradingViewSnapshot() {
        val expected = mapOf(
            AnalysisTimeframe.ONE_MINUTE to "2026-11-23T00:00:00Z",
            AnalysisTimeframe.FIVE_MINUTES to "2026-11-09T00:00:00Z",
            AnalysisTimeframe.FIFTEEN_MINUTES to "2026-10-01T00:00:00Z",
            AnalysisTimeframe.THIRTY_MINUTES to "2025-01-01T00:00:00Z",
            AnalysisTimeframe.ONE_HOUR to "2025-01-01T00:00:00Z",
            AnalysisTimeframe.TWO_HOURS to "2025-01-01T00:00:00Z"
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
    fun leftBoundaryAdvancesOnlyAtUtcMidnightByOneDayOfBars() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val beforeMidnight = reference + oneDay - 1L
            val atMidnight = reference + oneDay
            val first = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, beforeMidnight)
            val nextDay = TradingViewBasicHistoryWindow.startTimeMillis(timeframe, atMidnight)
            assertEquals(oneDay, nextDay - first, timeframe.apiInterval)
            assertEquals(
                oneDay / timeframe.durationMillis,
                (nextDay - first) / timeframe.durationMillis,
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun liveCandleCountIncludesTheCurrentFormingCandle() {
        AnalysisTimeframe.entries.forEach { timeframe ->
            val now = Instant.parse("2026-11-27T12:34:56Z").toEpochMilli()
            assertEquals(
                TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, now) + 1L,
                TradingViewBasicHistoryWindow.expectedLiveCandleCount(timeframe, now),
                timeframe.apiInterval
            )
        }
    }

    @Test
    fun referenceSnapshotHasExpectedCompletedWindowLengths() {
        val expectedCounts = mapOf(
            AnalysisTimeframe.ONE_MINUTE to 5_760L,
            AnalysisTimeframe.FIVE_MINUTES to 5_184L,
            AnalysisTimeframe.FIFTEEN_MINUTES to 5_472L,
            AnalysisTimeframe.THIRTY_MINUTES to 33_360L,
            AnalysisTimeframe.ONE_HOUR to 16_680L,
            AnalysisTimeframe.TWO_HOURS to 8_340L
        )
        expectedCounts.forEach { (timeframe, expected) ->
            assertEquals(
                expected,
                TradingViewBasicHistoryWindow.expectedCompletedCandleCount(timeframe, reference),
                timeframe.apiInterval
            )
            assertEquals(expected + 1L,
                TradingViewBasicHistoryWindow.expectedLiveCandleCount(timeframe, reference),
                timeframe.apiInterval)
        }
    }
}
