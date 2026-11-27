package com.javad.signal

import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Defines Javad's fixed historical dataset size.
 *
 * Every symbol is initialized with exactly 5,000 completed candles at the
 * selected chart timeframe. The end boundary is rounded down to the current
 * candle's open time, so the window contains the 5,000 immediately preceding
 * completed candles. No week/month/year calendar expansion is applied.
 *
 * The earliest returned candle becomes Pine bar_index 0 and the starting point
 * for the cumulative LuxAlgo Auto Threshold calculation. Toobit timestamps
 * are Unix milliseconds and UTC candle boundaries are used.
 */
object TradingViewBasicHistoryWindow {
    const val HISTORICAL_BARS: Int = 5_000

    fun endTimeMillis(
        timeframe: AnalysisTimeframe,
        serverTimeMillis: Long
    ): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        return serverTimeMillis - (serverTimeMillis % timeframe.durationMillis)
    }

    fun startTimeMillis(
        timeframe: AnalysisTimeframe,
        endTimeMillis: Long,
        zoneId: ZoneId = ZoneOffset.UTC
    ): Long {
        require(endTimeMillis > 0L) { "endTimeMillis must be positive" }
        // Retain the parameter for source compatibility; Toobit candle
        // boundaries are UTC epoch-aligned, not local-calendar aligned.
        require(zoneId == ZoneOffset.UTC || zoneId.rules.isFixedOffset) {
            "History windows must use a fixed UTC-compatible timezone"
        }
        val alignedEnd = endTimeMillis - (endTimeMillis % timeframe.durationMillis)
        return alignedEnd - HISTORICAL_BARS.toLong() * timeframe.durationMillis
    }
}
