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
     * The 45-minute Javad interval is built from three consecutive 15-minute
     * Toobit candles because Toobit does not publish a native 45m interval.
     *
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
        val sourceInterval = if (timeframe == AnalysisTimeframe.FORTY_FIVE_MINUTES) "15m"
            else timeframe.apiInterval
        val sourceDuration = intervalMillis(sourceInterval)
        val serverTime = getServerTimeMillis()
        val sourceCandles = mutableListOf<MarketCandle>()
        var cursor = startTimeMillis

        while (cursor < endTimeMillis) {
            val path = "/quote/v1/klines" +
                "?symbol=${encode(contract.exchangeSymbol)}" +
                "&interval=${encode(sourceInterval)}" +
                "&startTime=$cursor&endTime=$endTimeMillis&limit=$pageLimit"
            val rows = getJson(path)
            if (!rows.isArray || rows.size() == 0) break

            val page = rows.map(::parseKline).sortedBy { it.openTime }
            val fresh = page.filter { it.openTime >= cursor && it.openTime < endTimeMillis }
            if (fresh.isEmpty()) break
            sourceCandles += fresh
            val next = fresh.last().openTime + sourceDuration
            if (next <= cursor) throw ToobitApiException("Toobit kline pagination did not advance")
            cursor = next
            if (page.size < pageLimit) break
        }

        val completed = sourceCandles
            .distinctBy { it.openTime }
            .filter { it.openTime + sourceDuration <= minOf(serverTime, endTimeMillis) }
            .sortedBy { it.openTime }

        completed.zipWithNext().forEach { (previous, next) ->
            if (next.openTime - previous.openTime != sourceDuration) {
                throw ToobitApiException(
                    "Missing Toobit candles for $javadSymbol at $sourceInterval; refusing inaccurate analysis"
                )
            }
        }

        return if (timeframe == AnalysisTimeframe.FORTY_FIVE_MINUTES) {
            aggregateCandles(completed, 45L * 60_000L)
        } else completed
    }

    private fun getServerTimeMillis(): Long {
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

    private fun intervalMillis(interval: String): Long = when (interval) {
        "1m" -> 60_000L
        "5m" -> 5 * 60_000L
        "15m" -> 15 * 60_000L
        "30m" -> 30 * 60_000L
        "1h" -> 60 * 60_000L
        "2h" -> 2 * 60 * 60_000L
        else -> throw IllegalArgumentException("Unsupported Toobit source interval: $interval")
    }

    private fun aggregateCandles(
        candles: List<MarketCandle>,
        targetDurationMillis: Long
    ): List<MarketCandle> {
        if (candles.isEmpty()) return emptyList()
        val sourceDuration = 15L * 60_000L
        val buckets = candles.groupBy { (it.openTime / targetDurationMillis) * targetDurationMillis }
        return buckets.entries.sortedBy { it.key }.mapNotNull { (bucketStart, rows) ->
            val ordered = rows.sortedBy { it.openTime }
            val expectedCount = (targetDurationMillis / sourceDuration).toInt()
            val aligned = ordered.size == expectedCount &&
                ordered.first().openTime == bucketStart &&
                ordered.zipWithNext().all { (a, b) -> b.openTime - a.openTime == sourceDuration } &&
                ordered.last().openTime + sourceDuration == bucketStart + targetDurationMillis
            if (!aligned) null else MarketCandle(
                openTime = bucketStart,
                open = ordered.first().open,
                high = ordered.maxOf { it.high },
                low = ordered.minOf { it.low },
                close = ordered.last().close,
                volume = ordered.sumOf { it.volume }
            )
        }
    }
}
