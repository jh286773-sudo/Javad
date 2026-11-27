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
    fun paginationConnectsMoreThanFiveThousandCandlesAcrossPages() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        val calls = mutableListOf<Long>()
        val candles = client.collectPages(0L, 6_001L * duration, duration, 1_000) { cursor ->
            calls += cursor
            (0 until minOf(1_000L, (6_001L * duration - cursor) / duration).toInt()).map { offset -> testCandle(cursor + offset * duration) }
        }

        assertEquals(6_001, candles.size)
        assertEquals(7, calls.size)
        assertEquals(0L, candles.first().openTime)
        assertEquals(6_000L * duration, candles.last().openTime)
        assertEquals((0 until 6_001).map { it * duration }, candles.map { it.openTime })
    }

    @Test
    fun backwardPaginationConnectsMoreThanFiveThousandCandlesAcrossPages() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        val candles = (1L..6_001L).map { index -> testCandle(index * duration) }
        val calls = mutableListOf<Long>()

        val collected = client.collectPagesBackward(
            endTimeExclusiveMillis = 6_002L * duration,
            sourceDuration = duration,
            pageLimit = 1_000
        ) { cursor ->
            calls += cursor
            candles.filter { it.openTime < cursor }.takeLast(1_000)
        }

        assertEquals(6_001, collected.size)
        assertEquals(8, calls.size) // Seven data pages plus the final empty page.
        assertEquals(candles.first().openTime, collected.first().openTime)
        assertEquals(candles.last().openTime, collected.last().openTime)
        assertEquals(candles, collected)
    }

    @Test
    fun backwardPaginationContinuesAfterShortPagesUntilNoOlderCandlesRemain() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        val candles = (1L..5L).map { index -> testCandle(index * duration) }
        var calls = 0

        val collected = client.collectPagesBackward(
            endTimeExclusiveMillis = 6L * duration,
            sourceDuration = duration,
            pageLimit = 3
        ) { cursor ->
            calls++
            candles.filter { it.openTime < cursor }.takeLast(if (calls <= 2) 2 else 3)
        }

        assertEquals(4, calls) // 2 + 2 + 1 candles, then an empty page.
        assertEquals(candles, collected)
    }

    @Test
    fun backwardPaginationRejectsMissingCandlesAtPageBoundaries() {
        val client = ToobitMarketDataClient()
        val duration = 60_000L
        val candles = (1L..10L).map { index -> testCandle(index * duration) }

        assertFailsWith<ToobitApiException> {
            client.collectPagesBackward(
                endTimeExclusiveMillis = 11L * duration,
                sourceDuration = duration,
                pageLimit = 5
            ) { cursor ->
                if (cursor == 11L * duration) {
                    candles.filter { it.openTime < cursor }.takeLast(5)
                } else {
                    candles.filter { it.openTime < cursor - duration }.takeLast(5)
                }
            }
        }
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
}
