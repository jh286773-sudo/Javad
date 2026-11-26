package com.javad.signal

/**
 * The only analysis intervals exposed by Javad.
 * A single AnalysisSettings instance holds exactly one active timeframe.
 */
enum class AnalysisTimeframe(
    val apiInterval: String,
    val displayLabel: String
) {
    ONE_MINUTE("1m", "1 دقیقه"),
    FIVE_MINUTES("5m", "5 دقیقه"),
    FIFTEEN_MINUTES("15m", "15 دقیقه"),
    THIRTY_MINUTES("30m", "30 دقیقه"),
    FORTY_FIVE_MINUTES("45m", "45 دقیقه"),
    ONE_HOUR("1h", "1 ساعت"),
    TWO_HOURS("2h", "2 ساعت")
}

/**
 * Shared analysis settings: every selected symbol is evaluated using the
 * same single timeframe. Selecting another timeframe replaces the previous
 * selection; multiple simultaneous timeframes are not representable here.
 */
data class AnalysisSettings(
    val selectedTimeframe: AnalysisTimeframe = AnalysisTimeframe.FIFTEEN_MINUTES,
    val symbols: Set<String> = emptySet()
) {
    fun selectTimeframe(timeframe: AnalysisTimeframe): AnalysisSettings =
        copy(selectedTimeframe = timeframe)

    fun selectSymbols(newSymbols: Set<String>): AnalysisSettings =
        copy(symbols = newSymbols.map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet())
}
