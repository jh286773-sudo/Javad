package com.javad.signal

/**
 * Provides the completed-candle end boundary for Toobit history requests.
 *
 * There is deliberately no fixed historical bar count. The history loader
 * discovers the earliest candle available for each contract and timeframe;
 * that candle becomes Pine bar_index 0 and the origin of ta.cum().
 */
object TradingViewBasicHistoryWindow {
    fun endTimeMillis(
        timeframe: AnalysisTimeframe,
        serverTimeMillis: Long
    ): Long {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        return serverTimeMillis - (serverTimeMillis % timeframe.durationMillis)
    }
}
