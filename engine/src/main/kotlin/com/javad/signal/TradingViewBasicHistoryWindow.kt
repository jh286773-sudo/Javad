package com.javad.signal

import java.time.Instant

/**
 * JAVAD's UTC rolling history window.
 *
 * The left edge advances by exactly one UTC day at each UTC midnight.
 * The right edge is live: latestCandleOpenTimeMillis follows the open time
 * of the currently forming/latest market candle and advances on each new bar.
 * The REST history end remains exclusive at that candle's open time, so REST
 * seeds only completed bars; the WebSocket supplies the current forming bar.
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

    /** First open-time included in the rolling dataset; moves at UTC midnight only. */
    fun startTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        val elapsedUtcDays = ((serverTimeMillis - referenceTimeMillis) / DAY_MILLIS).coerceAtLeast(0L)
        val start = observedFirstCandleMillis(timeframe) + elapsedUtcDays * DAY_MILLIS
        require(start % timeframe.durationMillis == 0L) {
            "Configured history anchor is not aligned to ${timeframe.apiInterval}"
        }
        return start
    }

    /** Open time of the latest/current market candle; advances independently of UTC midnight. */
    fun latestCandleOpenTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        return serverTimeMillis - (serverTimeMillis % timeframe.durationMillis)
    }

    /** Exclusive REST end boundary: current candle open time, so REST returns completed candles only. */
    fun endTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long =
        latestCandleOpenTimeMillis(timeframe, serverTimeMillis)

    /** Number of completed candles fetched by REST (current forming candle excluded). */
    fun expectedCompletedCandleCount(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        val start = startTimeMillis(timeframe, serverTimeMillis)
        val end = endTimeMillis(timeframe, serverTimeMillis)
        require(start <= end) { "History start is later than its completed end boundary" }
        return (end - start) / timeframe.durationMillis
    }

    /** Total candles in the live window, including its latest forming candle. */
    fun expectedLiveCandleCount(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        val start = startTimeMillis(timeframe, serverTimeMillis)
        val latest = latestCandleOpenTimeMillis(timeframe, serverTimeMillis)
        require(start <= latest) { "History start is later than the latest market candle" }
        return (latest - start) / timeframe.durationMillis + 1L
    }
}
