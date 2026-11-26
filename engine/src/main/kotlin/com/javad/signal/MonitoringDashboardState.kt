package com.javad.signal

/**
 * One row in the home-screen monitoring list.
 * Every row carries the same currently selected timeframe.
 */
data class MonitoringTarget(
    val symbol: String,
    val timeframe: AnalysisTimeframe
)

/**
 * Home-screen state for searching Toobit perpetual instruments, managing
 * selected symbols, and showing the one active timeframe.
 *
 * availableSymbols must come from the Toobit USDT perpetual instruments API.
 * This class intentionally does not invent a local symbol catalogue.
 */
data class MonitoringDashboardState(
    val settings: AnalysisSettings = AnalysisSettings(),
    val availableSymbols: Set<String> = emptySet(),
    val searchQuery: String = "",
    val monitoringEnabled: Boolean = false
) {
    val activeTimeframe: AnalysisTimeframe
        get() = settings.selectedTimeframe

    val selectedSymbols: List<String>
        get() = settings.symbols.sorted()

    val visibleSearchResults: List<String>
        get() {
            val query = searchQuery.trim().uppercase()
            return availableSymbols
                .asSequence()
                .filter(AnalysisSettings::isSupportedSymbol)
                .filter { query.isEmpty() || it.contains(query) }
                .sorted()
                .toList()
        }

    /** Home-screen rows: selected symbols only, all on the same timeframe. */
    val homeScreenTargets: List<MonitoringTarget>
        get() = selectedSymbols.map { MonitoringTarget(it, activeTimeframe) }

    /** The exact targets the monitoring worker should analyse while enabled. */
    val monitoringTargets: List<MonitoringTarget>
        get() = if (monitoringEnabled) homeScreenTargets else emptyList()

    fun updateAvailableSymbols(symbols: Set<String>): MonitoringDashboardState {
        val normalized = symbols.map { it.trim().uppercase() }
        require(normalized.all(AnalysisSettings::isSupportedSymbol)) {
            "The instrument catalogue must contain only USDT perpetual symbols ending in USDT.P"
        }
        return copy(availableSymbols = normalized.toSet())
    }

    fun search(query: String): MonitoringDashboardState =
        copy(searchQuery = query)

    fun selectSymbol(symbol: String): MonitoringDashboardState {
        val normalized = symbol.trim().uppercase()
        require(normalized in availableSymbols) {
            "Symbol must exist in the current Toobit perpetual instrument catalogue"
        }
        return copy(settings = settings.addSymbol(normalized))
    }

    fun unselectSymbol(symbol: String): MonitoringDashboardState =
        copy(settings = settings.removeSymbol(symbol))

    fun selectTimeframe(timeframe: AnalysisTimeframe): MonitoringDashboardState =
        copy(settings = settings.selectTimeframe(timeframe))

    fun setMonitoringEnabled(enabled: Boolean): MonitoringDashboardState =
        copy(monitoringEnabled = enabled)
}
