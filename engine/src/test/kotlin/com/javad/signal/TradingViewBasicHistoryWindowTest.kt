package com.javad.signal

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingViewBasicHistoryWindowTest {
    @Test
    fun fiveMinuteBasicWindowAlignsToBeginningOfWeek() {
        val end = Instant.parse("2026-11-27T12:34:00Z").toEpochMilli()
        val start = TradingViewBasicHistoryWindow.startTimeMillis(
            AnalysisTimeframe.FIVE_MINUTES,
            end
        )

        assertEquals(
            Instant.parse("2026-11-09T00:00:00Z").toEpochMilli(),
            start
        )
    }

    @Test
    fun fifteenMinuteBasicWindowAlignsToBeginningOfMonth() {
        val end = Instant.parse("2026-11-27T12:34:00Z").toEpochMilli()
        val start = TradingViewBasicHistoryWindow.startTimeMillis(
            AnalysisTimeframe.FIFTEEN_MINUTES,
            end
        )

        assertEquals(
            Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(),
            start
        )
    }

    @Test
    fun thirtyMinuteBasicWindowAlignsToBeginningOfYear() {
        val end = Instant.parse("2026-11-27T12:34:00Z").toEpochMilli()
        val start = TradingViewBasicHistoryWindow.startTimeMillis(
            AnalysisTimeframe.THIRTY_MINUTES,
            end,
            ZoneOffset.UTC
        )

        assertEquals(
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            start
        )
    }
}
