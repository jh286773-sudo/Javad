package com.javad.signal

import java.time.Instant

/**
 * Reproduces the user's observed UTC daily-rolling TradingView history window.
 * The observed left edge is anchored independently for each supported native
 * Toobit timeframe at the 2026-11-27 00:00 UTC reference snapshot. At each
 * subsequent UTC midnight, the left edge advances by exactly 24 hours.
 *
 * This is an explicit JAVAD compatibility rule based on live chart observations;
 * it is not inferred from TradingView's Pine bar-limit API.
 */
object TradingViewBasicHistoryWindow {
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    private val referenceTimeMillis = Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()

    private fun observedFirstCandleMillis(timeframe: AnalysisTimeframe): Long {
        val firstCandle = when (timeframe) {
            AnalysisTimeframe.ONE_MINUTE -> "2026-11-23T00:00:00Z"
            AnalysisTimeframe.FIVE_MINUTES -> "2026-11-09T00:00:00Z"
            AnalysisTimeframe.FIFTEEN_MINUTES -> "2026-10-01T00:00:00Z"
            AnalysisTimeframe.THIRTY_MINUTES,
            AnalysisTimeframe.ONE_HOUR,
            AnalysisTimeframe.TWO_HOURS -> "2025-01-01T00:00:00Z"
        }
        return Instant.parse(firstCandle).toEpochMilli()
    }

    /** First open-time included in the rolling history dataset for this timeframe. */
    fun startTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        val elapsedUtcDays = ((serverTimeMillis - referenceTimeMillis) / DAY_MILLIS).coerceAtLeast(0L)
        val start = observedFirstCandleMillis(timeframe) + elapsedUtcDays * DAY_MILLIS
        require(start % timeframe.durationMillis == 0L) {
            "Configured history anchor is not aligned to ${timeframe.apiInterval}"
        }
        return start
    }

    /** Exclusive end boundary, rounded down so the forming candle is excluded. */
    fun endTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        return serverTimeMillis - (serverTimeMillis % timeframe.durationMillis)
    }

    /** Number of whole candles in the completed rolling window. */
    fun expectedCompletedCandleCount(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        val start = startTimeMillis(timeframe, serverTimeMillis)
        val end = endTimeMillis(timeframe, serverTimeMillis)
        require(start <= end) { "History start is later than its completed end boundary" }
        return (end - start) / timeframe.durationMillis
    }
}
