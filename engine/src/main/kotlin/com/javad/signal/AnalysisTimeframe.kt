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
 * Shared analysis settings: all selected Toobit USDT perpetual contracts
 * use the same single timeframe.
 *
 * Symbols use Toobit's perpetual notation, for example BTCUSDT.P.
 * The exchange instrument list will be supplied by the market-data layer;
 * this class validates selections but does not invent or hardcode that list.
 */
data class AnalysisSettings(
    val selectedTimeframe: AnalysisTimeframe = AnalysisTimeframe.FIFTEEN_MINUTES,
    val symbols: Set<String> = emptySet()
) {
    fun selectTimeframe(timeframe: AnalysisTimeframe): AnalysisSettings =
        copy(selectedTimeframe = timeframe)

    fun selectSymbols(newSymbols: Set<String>): AnalysisSettings {
        val normalized = newSymbols.map { it.trim().uppercase() }
        require(normalized.all(::isSupportedSymbol)) {
            "Only USDT-margined perpetual symbols ending in USDT.P are supported"
        }
        return copy(symbols = normalized.toSet())
    }

    fun addSymbol(symbol: String): AnalysisSettings {
        val normalized = symbol.trim().uppercase()
        require(isSupportedSymbol(normalized)) {
            "Only USDT-margined perpetual symbols ending in USDT.P are supported"
        }
        return copy(symbols = symbols + normalized)
    }

    fun removeSymbol(symbol: String): AnalysisSettings =
        copy(symbols = symbols - symbol.trim().uppercase())

    companion object {
        private val supportedSymbolPattern = Regex("^[A-Z0-9]+USDT\\.P$")

        fun isSupportedSymbol(symbol: String): Boolean =
            supportedSymbolPattern.matches(symbol.trim().uppercase())
    }
}
