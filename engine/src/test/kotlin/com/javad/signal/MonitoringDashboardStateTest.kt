package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitoringDashboardStateTest {
    private val catalogue = setOf("BTCUSDT.P", "ETHUSDT.P", "SOLUSDT.P")

    @Test
    fun searchFiltersTheExchangeCatalogue() {
        val state = MonitoringDashboardState().updateAvailableSymbols(catalogue).search("eth")
        assertEquals(listOf("ETHUSDT.P"), state.visibleSearchResults)
    }

    @Test
    fun homeScreenShowsSelectedSymbolsAndOneSharedTimeframe() {
        val state = MonitoringDashboardState()
            .updateAvailableSymbols(catalogue)
            .selectSymbol("BTCUSDT.P")
            .selectSymbol("ETHUSDT.P")
            .selectTimeframe(AnalysisTimeframe.FIVE_MINUTES)
        assertEquals(setOf("BTCUSDT.P", "ETHUSDT.P"), state.homeScreenTargets.map { it.symbol }.toSet())
        assertTrue(state.homeScreenTargets.all { it.timeframe == AnalysisTimeframe.FIVE_MINUTES })
        assertEquals(AnalysisTimeframe.FIVE_MINUTES, state.activeTimeframe)
    }

    @Test
    fun onlySelectedSymbolsAreMonitoredAndOnlyWhenMonitoringIsEnabled() {
        val stopped = MonitoringDashboardState()
            .updateAvailableSymbols(catalogue)
            .selectSymbol("BTCUSDT.P")
        assertTrue(stopped.monitoringTargets.isEmpty())
        val running = stopped.setMonitoringEnabled(true)
        assertEquals(listOf("BTCUSDT.P"), running.monitoringTargets.map { it.symbol })
        assertFalse(running.monitoringTargets.any { it.symbol == "ETHUSDT.P" })
    }
}
