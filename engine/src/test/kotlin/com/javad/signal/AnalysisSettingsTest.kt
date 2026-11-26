package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalysisSettingsTest {
    @Test
    fun selectsMultipleUsdtPerpetualSymbolsAndNormalizesThem() {
        val settings = AnalysisSettings()
            .selectSymbols(setOf(" btcusdt.p ", "ETHUSDT.P"))

        assertEquals(setOf("BTCUSDT.P", "ETHUSDT.P"), settings.symbols)
    }

    @Test
    fun rejectsSpotAndDatedContracts() {
        val settings = AnalysisSettings()

        assertFailsWith<IllegalArgumentException> {
            settings.selectSymbols(setOf("BTCUSDT"))
        }
        assertFailsWith<IllegalArgumentException> {
            settings.selectSymbols(setOf("BTCUSDT_20261225"))
        }
    }

    @Test
    fun exposesTheApprovedSingleTimeframeChoicesIncludingFortyFiveMinutes() {
        assertEquals(listOf("1m", "5m", "15m", "30m", "45m", "1h", "2h"), AnalysisTimeframe.entries.map { it.apiInterval })
    }

    @Test
    fun addingAndRemovingSymbolsDoesNotChangeSelectedTimeframe() {
        val settings = AnalysisSettings()
            .selectTimeframe(AnalysisTimeframe.ONE_HOUR)
            .addSymbol("BTCUSDT.P")
            .addSymbol("ETHUSDT.P")
            .removeSymbol("BTCUSDT.P")

        assertEquals(AnalysisTimeframe.ONE_HOUR, settings.selectedTimeframe)
        assertEquals(setOf("ETHUSDT.P"), settings.symbols)
        assertTrue(AnalysisSettings.isSupportedSymbol("1000PEPEUSDT.P"))
    }
}
