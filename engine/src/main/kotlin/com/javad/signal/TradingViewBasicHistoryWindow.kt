package com.javad.signal

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Selects the history start used by Javad.
 *
 * For the active 5-minute TradingView comparison, the user supplied and
 * confirmed 2026-11-09 00:00:00 UTC as the first chart candle. This is a fixed
 * anchor, not a rolling 5,000-bar window. Every 5-minute symbol starts its own
 * bar_index and cumulative Auto Threshold at this timestamp.
 *
 * Other intervals retain the TradingView Basic-plan approximation: 5,000
 * intraday bars aligned to the documented calendar boundary (week/month/year).
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

        if (timeframe == AnalysisTimeframe.FIVE_MINUTES) {
            return Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
        }

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
