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
}
