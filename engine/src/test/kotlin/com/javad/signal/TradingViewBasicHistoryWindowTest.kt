package com.javad.signal

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingViewBasicHistoryWindowTest {
    @Test
    fun everySupportedTimeframeUsesExactlyFiveThousandIntervals() {
        val serverTime = Instant.parse("2026-11-27T12:34:56.789Z").toEpochMilli()

        AnalysisTimeframe.entries.forEach { timeframe ->
            val end = TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime)
            val start = TradingViewBasicHistoryWindow.startTimeMillis(
                timeframe = timeframe,
                endTimeMillis = end,
                zoneId = ZoneOffset.UTC
            )

            assertEquals(0L, end % timeframe.durationMillis)
            assertEquals(
                TradingViewBasicHistoryWindow.HISTORICAL_BARS.toLong() * timeframe.durationMillis,
                end - start,
                "History window must contain exactly 5,000 ${timeframe.apiInterval} intervals"
            )
        }
    }

    @Test
    fun oneHourWindowCoversAboutTwoHundredEightDays() {
        val serverTime = Instant.parse("2026-11-27T12:34:56Z").toEpochMilli()
        val end = TradingViewBasicHistoryWindow.endTimeMillis(
            AnalysisTimeframe.ONE_HOUR,
            serverTime
        )
        val start = TradingViewBasicHistoryWindow.startTimeMillis(
            AnalysisTimeframe.ONE_HOUR,
            end
        )

        val days = (end - start) / (24L * 60L * 60L * 1000L)
        assertEquals(208L, days)
    }

    @Test
    fun fiveMinuteWindowCoversAboutSeventeenDays() {
        val serverTime = Instant.parse("2026-11-27T12:34:56Z").toEpochMilli()
        val end = TradingViewBasicHistoryWindow.endTimeMillis(
            AnalysisTimeframe.FIVE_MINUTES,
            serverTime
        )
        val start = TradingViewBasicHistoryWindow.startTimeMillis(
            AnalysisTimeframe.FIVE_MINUTES,
            end
        )

        val days = (end - start) / (24L * 60L * 60L * 1000L)
        assertEquals(17L, days)
    }

    @Test
    fun fifteenMinuteWindowCoversAboutFiftyTwoDays() {
        val serverTime = Instant.parse("2026-11-27T12:34:56Z").toEpochMilli()
        val end = TradingViewBasicHistoryWindow.endTimeMillis(
            AnalysisTimeframe.FIFTEEN_MINUTES,
            serverTime
        )
        val start = TradingViewBasicHistoryWindow.startTimeMillis(
            AnalysisTimeframe.FIFTEEN_MINUTES,
            end
        )

        val days = (end - start) / (24L * 60L * 60L * 1000L)
        assertEquals(52L, days)
    }
}
