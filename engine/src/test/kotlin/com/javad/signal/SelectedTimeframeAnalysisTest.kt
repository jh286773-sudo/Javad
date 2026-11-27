package com.javad.signal

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToobitMarketDataClientTest {
    @Test
    fun contractListKeepsOnlyActiveLinearUsdtPerpetuals() {
        val exchangeInfo = ObjectMapper().readTree(
            """
            {
              "contracts": [
                {
                  "symbol": "BTC-SWAP-USDT",
                  "underlying": "BTC",
                  "quoteAsset": "USDT",
                  "marginToken": "USDT",
                  "inverse": false,
                  "status": "TRADING"
                },
                {
                  "symbol": "ETH-SWAP-USDT",
                  "underlying": "ETH",
                  "quoteAsset": "USDT",
                  "marginToken": "USDT",
                  "inverse": false,
                  "status": "TRADING"
                },
                {
                  "symbol": "BTC-SWAP",
                  "underlying": "BTC",
                  "quoteAsset": "USDT",
                  "marginToken": "BTC",
                  "inverse": true,
                  "status": "TRADING"
                },
                {
                  "symbol": "DOGE-SWAP-USDT",
                  "underlying": "DOGE",
                  "quoteAsset": "USDT",
                  "marginToken": "USDT",
                  "inverse": false,
                  "status": "ONLINE"
                },
                {
                  "symbol": "SOL-SWAP-USDC",
                  "underlying": "SOL",
                  "quoteAsset": "USDC",
                  "marginToken": "USDC",
                  "inverse": false,
                  "status": "TRADING"
                }
              ]
            }
            """.trimIndent()
        )

        val contracts = ToobitMarketDataClient().parseContracts(exchangeInfo)

        assertEquals(listOf("BTCUSDT.P", "ETHUSDT.P"), contracts.map { it.javadSymbol })
        assertEquals(listOf("BTC-SWAP-USDT", "ETH-SWAP-USDT"), contracts.map { it.exchangeSymbol })
    }

    @Test
    fun malformedExchangeInfoWithoutContractsIsRejected() {
        val malformed = ObjectMapper().readTree("""{"symbols": []}""")

        assertFailsWith<ToobitApiException> {
            ToobitMarketDataClient().parseContracts(malformed)
        }
    }

    @Test
    fun paginationConnectsFiveFullThousandCandlePages() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        val calls = mutableListOf<Long>()
        val candles = client.collectPages(0L, 5_000L * duration, duration, 1_000) { cursor ->
            calls += cursor
            (0 until 1_000).map { offset -> testCandle(cursor + offset * duration) }
        }

        assertEquals(5_000, candles.size)
        assertEquals(5, calls.size)
        assertEquals(0L, candles.first().openTime)
        assertEquals(4_999L * duration, candles.last().openTime)
        assertEquals((0 until 5_000).map { it * duration }, candles.map { it.openTime })
    }

    @Test
    fun shortPageDoesNotStopPaginationEarly() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        val pageSizes = listOf(800, 1_000, 1_000, 1_000, 1_000, 200)
        var pageIndex = 0
        val candles = client.collectPages(0L, 5_000L * duration, duration, 1_000) { cursor ->
            val size = pageSizes[pageIndex++]
            (0 until size).map { offset -> testCandle(cursor + offset * duration) }
        }

        assertEquals(6, pageIndex)
        assertEquals(5_000, candles.size)
        assertEquals(4_999L * duration, candles.last().openTime)
    }

    @Test
    fun paginationRejectsMissingCandleBetweenPages() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        assertFailsWith<ToobitApiException> {
            client.collectPages(0L, 3L * duration, duration, 2) { cursor ->
                if (cursor == 0L) {
                    listOf(testCandle(0L), testCandle(2L * duration))
                } else {
                    listOf(testCandle(cursor))
                }
            }
        }
    }

    @Test
    fun paginationRejectsDuplicateCandles() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        assertFailsWith<ToobitApiException> {
            client.collectPages(0L, 2L * duration, duration, 2) { cursor ->
                if (cursor == 0L) {
                    listOf(testCandle(0L), testCandle(0L), testCandle(duration))
                } else {
                    listOf(testCandle(duration))
                }
            }
        }
    }

    @Test
    fun paginationRejectsEmptyPageBeforeRangeEnds() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        assertFailsWith<ToobitApiException> {
            client.collectPages(0L, 2L * duration, duration, 1) { cursor ->
                if (cursor == 0L) listOf(testCandle(0L)) else emptyList()
            }
        }
    }

    private fun testCandle(openTime: Long) = MarketCandle(
        openTime = openTime,
        open = 100.0,
        high = 101.0,
        low = 99.0,
        close = 100.5,
        volume = 10.0
    )
    @Test
    fun historyLoaderRejectsShortHistoryBeforeEnablingLiveAnalysis() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)
        val start = 1_700_000_100_000L
        val shortHistory = (0 until 4_999).map { index ->
            testCandle(start + index * AnalysisTimeframe.FIVE_MINUTES.durationMillis)
        }

        assertFailsWith<IllegalArgumentException> {
            analysis.loadHistory("BTCUSDT.P", shortHistory)
        }
        assertEquals(false, analysis.isHistoryLoaded("BTCUSDT.P"))
    }

    @Test
    fun historyLoaderAcceptsExactlyFiveThousandConsecutiveCandles() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)
        val interval = AnalysisTimeframe.FIVE_MINUTES.durationMillis
        val start = 1_700_000_100_000L
        val history = (0 until TradingViewBasicHistoryWindow.HISTORICAL_BARS).map { index ->
            testCandle(start + index * interval)
        }

        analysis.loadHistory("BTCUSDT.P", history)

        assertEquals(true, analysis.isHistoryLoaded("BTCUSDT.P"))
    }

    @Test
    fun historyLoaderRejectsGapInFiveThousandCandleHistory() {
        val settings = AnalysisSettings(
            selectedTimeframe = AnalysisTimeframe.FIVE_MINUTES,
            symbols = setOf("BTCUSDT.P")
        )
        val analysis = SelectedTimeframeAnalysis(settings)
        val interval = AnalysisTimeframe.FIVE_MINUTES.durationMillis
        val start = 1_700_000_100_000L
        val history = (0 until TradingViewBasicHistoryWindow.HISTORICAL_BARS).map { index ->
            val adjustedIndex = if (index >= 2_500) index + 1 else index
            testCandle(start + adjustedIndex * interval)
        }

        assertFailsWith<IllegalArgumentException> {
            analysis.loadHistory("BTCUSDT.P", history)
        }
        assertEquals(false, analysis.isHistoryLoaded("BTCUSDT.P"))
    }

}
