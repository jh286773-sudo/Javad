package com.javad.signal

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingViewBasicHistoryWindowTest {
    @Test
    fun endBoundaryIsRoundedDownToSelectedTimeframe() {
        val serverTime = Instant.parse("2026-11-27T12:34:56.789Z").toEpochMilli()

        AnalysisTimeframe.entries.forEach { timeframe ->
            val end = TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime)
            assertEquals(0L, end % timeframe.durationMillis)
            assertEquals(
                serverTime - serverTime % timeframe.durationMillis,
                end
            )
        }
    }

    @Test
    fun endBoundaryDoesNotIncludeTheCurrentlyFormingCandle() {
        val timeframe = AnalysisTimeframe.FIFTEEN_MINUTES
        val serverTime = Instant.parse("2026-11-27T12:34:56Z").toEpochMilli()
        val end = TradingViewBasicHistoryWindow.endTimeMillis(timeframe, serverTime)

        assertEquals(0L, end % timeframe.durationMillis)
        assertEquals(true, end <= serverTime)
        assertEquals(true, serverTime - end < timeframe.durationMillis)
    }
}
