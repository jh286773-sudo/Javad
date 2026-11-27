package com.javad.signal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live public-market-data parity test. It intentionally uses only Toobit
 * public market-data endpoints; no account or trading credentials are used.
 */
class ToobitLiveHistoryParityTest {
    @Test
    fun btcAllAvailableToobitHistoryKeepsOneContinuousLuxAlgoAutoAndFvgState() {
        val client = ToobitMarketDataClient()
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val serverTime = client.getServerTimeMillis()
        val endTime = serverTime - serverTime % interval
        val candles = client.getAllHistoricalCandles(
            javadSymbol = "BTCUSDT.P",
            timeframe = timeframe,
            endTimeMillis = endTime,
            pageLimit = 1_000
        )

        assertTrue(candles.size > 3_500, "Toobit full BTC history must exceed the 3,500-candle sample")
        candles.zipWithNext().forEach { (previous, next) ->
            assertEquals(interval, next.openTime - previous.openTime, "BTC full-history gap")
        }

        val processor = LuxAlgoFvgAutoProcessor(timeframe)
        var cumulativeAbsoluteDelta = 0.0
        var bullishCount = 0
        var bearishCount = 0
        var processed = 0
        candles.chunked(1_000).forEach { page ->
            page.forEach { candle ->
                val previous = candles.getOrNull(processed - 1)
                val twoBarsAgo = candles.getOrNull(processed - 2)
                val delta = previous?.let {
                    (it.close - it.open) / it.open * 100.0
                }
                if (delta != null) cumulativeAbsoluteDelta += abs(delta)

                val expectedThreshold = if (processed == 0) null
                    else cumulativeAbsoluteDelta / processed.toDouble() * 2.0
                val evaluation = processor.process(candle)
                assertEquals(processed.toLong(), evaluation.chartBarIndex, "BTC bar_index at $processed")
                if (expectedThreshold == null) {
                    assertEquals(null, evaluation.autoThreshold, "BTC Auto threshold at bar 0")
                } else {
                    assertNotNull(evaluation.autoThreshold)
                    assertEquals(
                        expectedThreshold,
                        evaluation.autoThreshold,
                        1e-10,
                        "BTC Auto threshold at bar $processed"
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
                assertEquals(expectedBullish, evaluation.bullishFvg, "BTC bullish FVG at bar $processed")
                assertEquals(expectedBearish, evaluation.bearishFvg, "BTC bearish FVG at bar $processed")
                if (evaluation.bullishFvg) bullishCount++
                if (evaluation.bearishFvg) bearishCount++
                processed++
            }
        }

        assertEquals(candles.size, processed)
        assertEquals(candles.size - 1L, processor.processHistory(emptyList()).size.toLong() + processed - 1L)
        println(
            "TOOBIT_FULL_HISTORY_PARITY symbol=BTCUSDT.P timeframe=5m candles=$processed " +
                "first=${candles.first().openTime} last=${candles.last().openTime} " +
                "bullish=$bullishCount bearish=$bearishCount pages=${candles.chunked(1_000).size}"
        )
    }

    @Test
    fun btcAndEthLast3500FiveMinuteCandlesMatchLuxAlgoReferenceAndPageProcessing() {
        val client = ToobitMarketDataClient()
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val serverTime = client.getServerTimeMillis()
        val endTime = serverTime - serverTime % interval
        val startTime = endTime - 3_500L * interval

        listOf("BTCUSDT.P", "ETHUSDT.P").forEach { symbol ->
            val candles = client.getHistoricalCandles(
                javadSymbol = symbol,
                timeframe = timeframe,
                startTimeMillis = startTime,
                endTimeMillis = endTime,
                pageLimit = 1_000
            )

            assertEquals(3_500, candles.size, "$symbol must return exactly 3,500 completed candles")
            assertEquals(startTime, candles.first().openTime)
            assertEquals(endTime - interval, candles.last().openTime)
            candles.zipWithNext().forEach { (previous, next) ->
                assertEquals(interval, next.openTime - previous.openTime, "$symbol history gap")
            }

            val singleProcessor = LuxAlgoFvgAutoProcessor(timeframe)
            val singlePass = singleProcessor.processHistory(candles)
            val pagedProcessor = LuxAlgoFvgAutoProcessor(timeframe)
            val pagedPass = candles.chunked(1_000).flatMap { page ->
                page.map(pagedProcessor::process)
            }

            assertEquals(singlePass, pagedPass, "$symbol results changed at a 1,000-candle page boundary")
            assertEquals(0L, singlePass.first().chartBarIndex)
            assertEquals(3_499L, singlePass.last().chartBarIndex)

            var cumulativeAbsoluteDelta = 0.0
            candles.forEachIndexed { index, candle ->
                val previous = candles.getOrNull(index - 1)
                val twoBarsAgo = candles.getOrNull(index - 2)
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
                "TOOBIT_LUXALGO_PARITY symbol=$symbol timeframe=5m candles=${candles.size} " +
                    "first=${candles.first().openTime} last=${candles.last().openTime} " +
                    "bullish=$bullishCount bearish=$bearishCount pages=4"
            )
        }
    }
}
