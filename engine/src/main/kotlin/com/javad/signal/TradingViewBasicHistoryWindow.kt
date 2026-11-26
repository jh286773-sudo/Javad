package com.javad.signal

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Reconstructs the Basic-plan TradingView intraday dataset start.
 *
 * TradingView documents 5,000 historical intraday bars, with the start aligned
 * to the beginning of a calendar period depending on chart interval:
 * 1-14m -> week, 15-29m -> month, 30-1439m -> year.
 *
 * Toobit timestamps are Unix milliseconds. UTC is used as the exchange-time
 * alignment assumption for Toobit perpetual charts; exact parity still depends
 * on the timezone and first available candle used by the TradingView symbol.
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
