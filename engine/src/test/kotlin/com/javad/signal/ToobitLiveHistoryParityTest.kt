package com.javad.signal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live public-market-data parity test. It uses only Toobit's public market
 * endpoints and does not require account or trading credentials.
 */
class ToobitLiveHistoryParityTest {
    @Test
    fun btcAndEthFullAvailableFiveMinuteHistoryMatchLuxAlgoReferenceAndPageProcessing() {
        val client = ToobitMarketDataClient()
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val serverTime = client.getServerTimeMillis()
        val endTime = serverTime - serverTime % interval

        listOf("BTCUSDT.P", "ETHUSDT.P").forEach { symbol ->
            val allHistory = client.getAllHistoricalCandles(
                javadSymbol = symbol,
                timeframe = timeframe,
                endTimeMillis = endTime,
                pageLimit = 1_000
            )

            assertTrue(
                allHistory.size > 1_000,
                "$symbol should span multiple Toobit pages; received ${allHistory.size}"
            )
            allHistory.zipWithNext().forEach { (previous, next) ->
                assertEquals(interval, next.openTime - previous.openTime, "$symbol full-history gap")
            }

            // Independently retrieve the exact discovered range in bounded
            // forward pages and require byte-for-byte OHLCV equality.
            val forwardHistory = client.getHistoricalCandles(
                javadSymbol = symbol,
                timeframe = timeframe,
                startTimeMillis = allHistory.first().openTime,
                endTimeMillis = allHistory.last().openTime + interval,
                pageLimit = 1_000
            )
            assertEquals(allHistory, forwardHistory, "$symbol backward/forward history mismatch")

            val singlePass = LuxAlgoFvgAutoProcessor(timeframe).processHistory(allHistory)
            val pagedProcessor = LuxAlgoFvgAutoProcessor(timeframe)
            val pagedPass = allHistory.chunked(1_000).flatMap { page ->
                page.map(pagedProcessor::process)
            }

            assertEquals(singlePass, pagedPass, "$symbol calculation changed at a page boundary")
            assertEquals(0L, singlePass.first().chartBarIndex)
            assertEquals(allHistory.size.toLong() - 1L, singlePass.last().chartBarIndex)

            var cumulativeAbsoluteDelta = 0.0
            allHistory.forEachIndexed { index, candle ->
                val previous = allHistory.getOrNull(index - 1)
                val twoBarsAgo = allHistory.getOrNull(index - 2)
                val delta = previous?.let {
                    (it.close - it.open) / it.open * 100.0
                }
                if (delta != null) cumulativeAbsoluteDelta += abs(delta)

                val expectedThreshold = if (index == 0) null
                    else cumulativeAbsoluteDelta / index.toDouble() * 2.0
                val evaluation = singlePass[index]
                if (expectedThreshold == null) {
                    assertEquals(null, evaluation.autoThreshold, "$symbol Auto threshold at bar 0")
                } else {
                    assertNotNull(evaluation.autoThreshold)
                    assertEquals(
                        expectedThreshold,
                        evaluation.autoThreshold,
                        1e-10,
                        "$symbol Auto threshold at bar $index"
                    )
                }

                val expectedBullish = delta != null && expectedThreshold != null && twoBarsAgo != null &&
                    candle.low > twoBarsAgo.high &&
                    previous!!.close > twoBarsAgo.high &&
                    delta > expectedThreshold
                val expectedBearish = delta != null && expectedThreshold != null && twoBarsAgo != null &&
                    candle.high < twoBarsAgo.low &&
                    previous!!.close < twoBarsAgo.low &&
                    -delta > expectedThreshold
                assertEquals(expectedBullish, evaluation.bullishFvg, "$symbol bullish FVG at bar $index")
                assertEquals(expectedBearish, evaluation.bearishFvg, "$symbol bearish FVG at bar $index")
            }

            val bullishCount = singlePass.count { it.bullishFvg }
            val bearishCount = singlePass.count { it.bearishFvg }
            println(
                "TOOBIT_LUXALGO_PARITY symbol=$symbol timeframe=5m candles=${allHistory.size} " +
                    "first=${allHistory.first().openTime} last=${allHistory.last().openTime} " +
                    "bullish=$bullishCount bearish=$bearishCount pages=${allHistory.chunked(1_000).size}"
            )
        }
    }
}
