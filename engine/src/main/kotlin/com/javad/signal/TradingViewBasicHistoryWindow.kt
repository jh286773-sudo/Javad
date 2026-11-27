package com.javad.signal

import java.time.Instant

/**
 * Calculates the exact rolling history window from Toobit's latest completed
 * candle and the configured target candle count for the selected timeframe.
 *
 * The current/forming candle is excluded from REST history and supplied by
 * WebSocket as a preview. At UTC midnight the left edge advances by one day.
 */
object TradingViewBasicHistoryWindow {
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /**
     * User-confirmed history counts at the end-of-day reference boundary.
     * These are per-timeframe counts, not a universal fixed cap.
     */
    fun targetCandleCount(timeframe: AnalysisTimeframe): Long = when (timeframe) {
        AnalysisTimeframe.ONE_MINUTE -> 7_200L
        AnalysisTimeframe.FIVE_MINUTES -> 5_472L
        AnalysisTimeframe.FIFTEEN_MINUTES -> 5_568L
        AnalysisTimeframe.THIRTY_MINUTES -> 33_408L
        AnalysisTimeframe.ONE_HOUR -> 7_944L
        AnalysisTimeframe.TWO_HOURS -> 8_352L
    }

    /** Open time of the current/forming candle, aligned to the selected interval. */
    fun latestCandleOpenTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        return serverTimeMillis - (serverTimeMillis % timeframe.durationMillis)
    }

    /**
     * Exclusive REST end boundary. The candle opening at this timestamp is
     * forming and must not be included in the completed historical dataset.
     */
    fun endTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long =
        latestCandleOpenTimeMillis(timeframe, serverTimeMillis)

    /**
     * Number of candle slots from the current candle open up to, but excluding,
     * the next UTC midnight. This includes the current forming candle slot.
     */
    fun remainingCandleCountUntilNextUtcMidnight(
        timeframe: AnalysisTimeframe,
        serverTimeMillis: Long
    ): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        val currentOpen = latestCandleOpenTimeMillis(timeframe, serverTimeMillis)
        val nextUtcMidnight = (serverTimeMillis / DAY_MILLIS + 1L) * DAY_MILLIS
        val remainingMillis = nextUtcMidnight - currentOpen
        require(remainingMillis >= 0L && remainingMillis % timeframe.durationMillis == 0L) {
            "UTC midnight is not aligned to the selected timeframe"
        }
        return remainingMillis / timeframe.durationMillis
    }

    /**
     * Completed candles to load, including the latest completed candle.
     * Subtracting the remaining slots from the timeframe's target count makes
     * the earliest candle move automatically at the UTC day boundary.
     */
    fun expectedCompletedCandleCount(
        timeframe: AnalysisTimeframe,
        serverTimeMillis: Long
    ): Long {
        val count = targetCandleCount(timeframe) -
            remainingCandleCountUntilNextUtcMidnight(timeframe, serverTimeMillis)
        require(count > 0L) {
            "Not enough completed candles exist yet for the configured " +
                timeframe.apiInterval + " history window"
        }
        return count
    }

    /** First candle open time, derived from the actual latest completed candle. */
    fun startTimeMillis(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long {
        val currentOpen = latestCandleOpenTimeMillis(timeframe, serverTimeMillis)
        val latestCompletedOpen = currentOpen - timeframe.durationMillis
        val completedCount = expectedCompletedCandleCount(timeframe, serverTimeMillis)
        val start = latestCompletedOpen - (completedCount - 1L) * timeframe.durationMillis
        require(start > 0L && start % timeframe.durationMillis == 0L) {
            "Calculated history start is invalid for " + timeframe.apiInterval
        }
        return start
    }

    /** Live window count adds the current forming candle as an uncommitted preview. */
    fun expectedLiveCandleCount(timeframe: AnalysisTimeframe, serverTimeMillis: Long): Long =
        expectedCompletedCandleCount(timeframe, serverTimeMillis) + 1L

    /** UTC midnight timestamp strictly after the supplied exchange time. */
    fun nextUtcMidnightMillis(serverTimeMillis: Long): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        return (serverTimeMillis / DAY_MILLIS + 1L) * DAY_MILLIS
    }
}
