package com.javad.signal

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Selects the history start used by Javad.
 *
 * Uses the TradingView Basic-plan intraday history window: 5,000 bars back,
 * aligned to the documented calendar boundary (week/month/year). For 5m,
 * the threshold date is aligned to the beginning of that UTC week.
 * The first returned candle becomes Pine bar_index 0 and the start of ta.cum.
 * Toobit timestamps are Unix milliseconds and UTC is used for alignment.
 */
object TradingViewBasicHistoryWindow {
    const val HISTORICAL_BARS: Int = 5_000

    fun startTimeMillis(
        timeframe: AnalysisTimeframe,
        endTimeMillis: Long,
        zoneId: ZoneId = ZoneOffset.UTC
    ): Long {
        require(endTimeMillis > 0L) { "endTimeMillis must be positive" }

        val lookbackMillis = HISTORICAL_BARS.toLong() * timeframe.durationMillis
        val threshold = Instant.ofEpochMilli(endTimeMillis - lookbackMillis).atZone(zoneId)
        val alignedStart = when (timeframe.durationMillis / 60_000L) {
            in 1L..14L -> threshold.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zoneId)
            in 15L..29L -> threshold.toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(zoneId)
            in 30L..1439L -> threshold.toLocalDate()
                .withDayOfYear(1)
                .atStartOfDay(zoneId)
            else -> threshold
        }
        return alignedStart.toInstant().toEpochMilli()
    }
}
