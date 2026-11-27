package com.javad.signal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live public-market-data integration test. It validates a bounded recent
 * range that the public endpoint can serve, plus identical one-pass/paged
 * processor behavior. The full observed TradingView rolling window is tested
 * separately and is rejected if Toobit cannot supply its exact first candle.
 */
class ToobitLiveHistoryParityTest {
    @Test
    fun btcAndEthRecentFiveMinuteHistoryMatchesLuxAlgoFormulaAndPageProcessing() {
        val client = ToobitMarketDataClient()
        val timeframe = AnalysisTimeframe.FIVE_MINUTES
        val interval = timeframe.durationMillis
        val serverTime = client.getServerTimeMillis()
        val endTime = serverTime - serverTime % interval
        val requestedCandles = 1_500L
        val startTime = endTime - requestedCandles * interval

        listOf("BTCUSDT.P", "ETHUSDT.P").forEach { symbol ->
            val history = client.getHistoricalCandles(
                javadSymbol = symbol,
                timeframe = timeframe,
                startTimeMillis = startTime,
                endTimeMillis = endTime,
                pageLimit = 1_000
            )

            assertEquals(requestedCandles.toInt(), history.size, "$symbol recent candle count")
            history.zipWithNext().forEach { (previous, next) ->
                assertEquals(interval, next.openTime - previous.openTime, "$symbol history gap")
            }

            val forwardHistory = client.getHistoricalCandles(
                javadSymbol = symbol,
                timeframe = timeframe,
                startTimeMillis = history.first().openTime,
                endTimeMillis = history.last().openTime + interval,
                pageLimit = 1_000
            )
            assertEquals(history, forwardHistory, "$symbol repeated REST range mismatch")

            val singlePass = LuxAlgoFvgAutoProcessor(timeframe, useHeikinAshi = true).processHistory(history)
            val pagedProcessor = LuxAlgoFvgAutoProcessor(timeframe, useHeikinAshi = true)
            val pagedPass = history.chunked(1_000).flatMap { page ->
                page.map(pagedProcessor::process)
            }

            assertEquals(singlePass, pagedPass, "$symbol calculation changed at a page boundary")
            assertEquals(0L, singlePass.first().chartBarIndex)
            assertEquals(history.size.toLong() - 1L, singlePass.last().chartBarIndex)

            val analysisHistory = toHeikinAshi(history)
            var cumulativeAbsoluteDelta = 0.0
            analysisHistory.forEachIndexed { index, candle ->
                val previous = analysisHistory.getOrNull(index - 1)
                val twoBarsAgo = analysisHistory.getOrNull(index - 2)
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
                "TOOBIT_LUXALGO_PARITY symbol=$symbol timeframe=5m candles=${history.size} " +
                    "first=${history.first().openTime} last=${history.last().openTime} " +
                    "bullish=$bullishCount bearish=$bearishCount pages=${history.chunked(1_000).size}"
            )
        }
    }

    /** Independent HA reference series for checking the production analysis path. */
    private fun toHeikinAshi(source: List<MarketCandle>): List<MarketCandle> {
        var previousOpen: Double? = null
        var previousClose: Double? = null
        return source.map { candle ->
            val haClose = (candle.open + candle.high + candle.low + candle.close) / 4.0
            val haOpen = if (previousOpen == null || previousClose == null) {
                (candle.open + candle.close) / 2.0
            } else {
                (previousOpen + previousClose) / 2.0
            }
            val ha = MarketCandle(
                openTime = candle.openTime,
                open = haOpen,
                high = maxOf(candle.high, haOpen, haClose),
                low = minOf(candle.low, haOpen, haClose),
                close = haClose,
                volume = candle.volume
            )
            previousOpen = ha.open
            previousClose = ha.close
            ha
        }
    }
}
