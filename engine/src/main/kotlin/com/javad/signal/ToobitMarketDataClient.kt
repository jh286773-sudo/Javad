package com.javad.signal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Public, read-only Toobit USDT-M perpetual market-data adapter.
 *
 * Javad symbols (for example BTCUSDT.P) are deliberately kept separate from
 * Toobit's API contract identifiers (for example BTC-SWAP-USDT).
 * No account credentials or trading endpoints are used here.
 */
data class ToobitPerpetualContract(
    val javadSymbol: String,
    val exchangeSymbol: String,
    val baseAsset: String,
    val quoteAsset: String
)

data class MarketCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

class ToobitApiException(message: String) : RuntimeException(message)

class ToobitMarketDataClient(
    private val baseUrl: String = "https://api.toobit.com",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val json: ObjectMapper = ObjectMapper()
) {
    /** Returns only active, linear, USDT-margined perpetual contracts. */
    fun listUsdtPerpetualContracts(): List<ToobitPerpetualContract> {
        return parseContracts(getJson("/api/v1/exchangeInfo"))
    }

    /** Pure exchangeInfo parser, separated so the contract filter can be unit-tested. */
    internal fun parseContracts(root: JsonNode): List<ToobitPerpetualContract> {
        val contracts = root.path("contracts")
        if (!contracts.isArray) throw ToobitApiException("Toobit exchangeInfo has no contracts array")

        return contracts.mapNotNull { item ->
            val exchangeSymbol = item.path("symbol").asText("")
            val base = item.path("underlying").asText("")
            val quote = item.path("quoteAsset").asText("")
            val margin = item.path("marginToken").asText("")
            val inverse = item.path("inverse").asBoolean(true)
            val status = item.path("status").asText("")
            if (exchangeSymbol.endsWith("-SWAP-USDT") &&
                base.isNotBlank() && quote == "USDT" && margin == "USDT" &&
                !inverse && status == "TRADING"
            ) {
                ToobitPerpetualContract(
                    javadSymbol = "${base}USDT.P",
                    exchangeSymbol = exchangeSymbol,
                    baseAsset = base,
                    quoteAsset = quote
                )
            } else null
        }.distinctBy { it.javadSymbol }.sortedBy { it.javadSymbol }
    }

    /**
     * Loads completed historical candles in ascending open-time order.
     * Incomplete live candles are excluded using Toobit's server clock. Missing
     * source candles are rejected rather than silently shifting LuxAlgo's
     * cumulative threshold and bar index.
     */
    fun getHistoricalCandles(
        javadSymbol: String,
        timeframe: AnalysisTimeframe,
        startTimeMillis: Long,
        endTimeMillis: Long,
        pageLimit: Int = 1000
    ): List<MarketCandle> {
        require(startTimeMillis < endTimeMillis) { "startTimeMillis must be before endTimeMillis" }
        require(pageLimit in 1..1000) { "Toobit kline page limit must be between 1 and 1000" }

        val contract = findContract(javadSymbol)
        val sourceInterval = timeframe.apiInterval
        val sourceDuration = timeframe.durationMillis
        val serverTime = getServerTimeMillis()
        val sourceCandles = collectPages(
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            sourceDuration = sourceDuration,
            pageLimit = pageLimit
        ) { cursor ->
            val requestPath = "/quote/v1/klines" +
                "?symbol=${encode(contract.exchangeSymbol)}" +
                "&interval=${encode(sourceInterval)}" +
                "&startTime=$cursor&endTime=$endTimeMillis&limit=$pageLimit"
            val rows = getJson(requestPath)
            if (!rows.isArray) {
                throw ToobitApiException("Toobit returned a non-array kline page")
            }
            rows.map(::parseKline)
        }

        val completed = sourceCandles
            .filter { it.openTime + sourceDuration <= minOf(serverTime, endTimeMillis) }
            .sortedBy { it.openTime }

        if (completed.isNotEmpty() && completed.first().openTime != startTimeMillis) {
            throw ToobitApiException(
                "Toobit history for $javadSymbol starts at ${completed.first().openTime}, " +
                    "not the required dataset anchor $startTimeMillis; refusing to shift bar_index"
            )
        }

        completed.zipWithNext().forEach { (previous, next) ->
            if (next.openTime - previous.openTime != sourceDuration) {
                throw ToobitApiException(
                    "Missing Toobit candles for $javadSymbol at $sourceInterval; refusing inaccurate analysis"
                )
            }
        }

        return completed
    }

    /**
     * Loads exactly 5,000 completed candles for the selected chart timeframe.
     * The end boundary is rounded down to the current candle open, then the
     * preceding 5,000 intervals are fetched in pages of at most 1,000.
     * Fewer or more candles are rejected: Auto Threshold must start at the
     * earliest candle of this fixed dataset (bar_index 0).
     */
    fun getAllHistoricalCandles(
        javadSymbol: String,
        timeframe: AnalysisTimeframe,
        endTimeMillis: Long,
        pageLimit: Int = 1000
    ): List<MarketCandle> {
        val historyEnd = TradingViewBasicHistoryWindow.endTimeMillis(
            timeframe = timeframe,
            serverTimeMillis = endTimeMillis
        )
        val historyStart = TradingViewBasicHistoryWindow.startTimeMillis(
            timeframe = timeframe,
            endTimeMillis = historyEnd
        )
        val candles = getHistoricalCandles(
            javadSymbol = javadSymbol,
            timeframe = timeframe,
            startTimeMillis = historyStart,
            endTimeMillis = historyEnd,
            pageLimit = pageLimit
        )
        if (candles.size != TradingViewBasicHistoryWindow.HISTORICAL_BARS) {
            throw ToobitApiException(
                "Expected exactly ${TradingViewBasicHistoryWindow.HISTORICAL_BARS} completed candles " +
                    "for $javadSymbol at ${timeframe.apiInterval}, but Toobit returned ${candles.size}; " +
                    "refusing to calculate Auto Threshold on a shortened dataset"
            )
        }
        return candles
    }

    /**
     * Fetches every page until the requested time range is exhausted.
     * A short page is not an end-of-history signal: exchanges may return fewer
     * rows than the requested limit while more history is still available.
     * Every requested interval must appear exactly once and in sequence.
     */
    internal fun collectPages(
        startTimeMillis: Long,
        endTimeMillis: Long,
        sourceDuration: Long,
        pageLimit: Int,
        fetchPage: (Long) -> List<MarketCandle>
    ): List<MarketCandle> {
        require(startTimeMillis < endTimeMillis)
        require(sourceDuration > 0L)
        require(pageLimit in 1..1000)

        val collected = mutableListOf<MarketCandle>()
        val seenOpenTimes = mutableSetOf<Long>()
        var cursor = startTimeMillis

        while (cursor < endTimeMillis) {
            val page = fetchPage(cursor).sortedBy { it.openTime }
            if (page.size > pageLimit) {
                throw ToobitApiException("Toobit returned more candles than the requested page limit")
            }
            if (page.isEmpty()) {
                throw ToobitApiException("Toobit returned an empty page before the requested history range was complete")
            }

            val fresh = page.filter { it.openTime >= cursor && it.openTime < endTimeMillis }
            if (fresh.isEmpty()) {
                throw ToobitApiException("Toobit pagination made no progress at $cursor")
            }
            if (fresh.first().openTime != cursor) {
                throw ToobitApiException("Missing Toobit candle at $cursor; refusing incomplete history")
            }

            fresh.forEach { candle ->
                if (!seenOpenTimes.add(candle.openTime)) {
                    throw ToobitApiException("Duplicate Toobit candle at ${candle.openTime}")
                }
                if (candle.openTime != cursor) {
                    throw ToobitApiException("Missing or out-of-order Toobit candle at $cursor")
                }
                collected += candle
                cursor += sourceDuration
            }
        }

        return collected
    }

    /** Returns Toobit's server clock for history-window boundaries. */
    fun getServerTimeMillis(): Long {
        val response = getJson("/api/v1/time")
        val serverTime = response.path("serverTime").asLong(0L)
        if (serverTime <= 0L) throw ToobitApiException("Toobit returned an invalid server time")
        return serverTime
    }

    private fun findContract(javadSymbol: String): ToobitPerpetualContract =
        listUsdtPerpetualContracts().firstOrNull {
            it.javadSymbol.equals(javadSymbol.trim(), ignoreCase = true)
        } ?: throw IllegalArgumentException("Unsupported or inactive Toobit USDT perpetual: $javadSymbol")

    private fun getJson(pathAndQuery: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + pathAndQuery))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ToobitApiException("Toobit returned HTTP ${response.statusCode()}")
        }
        return try {
            json.readTree(response.body())
        } catch (error: Exception) {
            throw ToobitApiException("Could not parse Toobit response: ${error.message}")
        }
    }

    private fun parseKline(row: JsonNode): MarketCandle {
        require(row.isArray && row.size() >= 6) { "Unexpected Toobit kline row" }
        val candle = MarketCandle(
            openTime = row[0].asLong(),
            open = row[1].asDouble(),
            high = row[2].asDouble(),
            low = row[3].asDouble(),
            close = row[4].asDouble(),
            volume = row[5].asDouble()
        )
        if (candle.openTime <= 0L ||
            !listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() } ||
            candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0 ||
            candle.volume < 0.0 || candle.high < maxOf(candle.open, candle.close) ||
            candle.low > minOf(candle.open, candle.close) || candle.high < candle.low
        ) {
            throw ToobitApiException("Toobit returned an invalid kline row")
        }
        return candle
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
