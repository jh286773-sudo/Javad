package com.javad.signal

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ToobitCandleUpdate(
    val javadSymbol: String,
    val candle: MarketCandle,
    /** True only after the exchange starts publishing the next source candle. */
    val isClosed: Boolean,
    /** True when one or more expected source candles were skipped. */
    val gapDetected: Boolean = false
)

/**
 * Public Toobit USDT-M kline stream. Subscribe only after loading history so
 * the caller can continue the LuxAlgo cumulative calculation chronologically.
 * The caller owns reconnect/recovery policy (implemented by the Android service).
 */
class ToobitKlineStream(
    private val onUpdate: (ToobitCandleUpdate) -> Unit,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val json: ObjectMapper = ObjectMapper()
) : AutoCloseable {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var pingTask: ScheduledFuture<*>? = null
    private val previousBySymbol = mutableMapOf<String, MarketCandle>()
    private val exchangeToJavad = mutableMapOf<String, String>()
    private val lock = Any()
    private val fifteenMinuteBars = mutableMapOf<String, MutableMap<Long, MarketCandle>>()
    private val last45MinuteBucketBySymbol = mutableMapOf<String, Long>()

    fun connect(
        contracts: List<ToobitPerpetualContract>,
        timeframe: AnalysisTimeframe
    ): CompletableFuture<Void> {
        require(contracts.isNotEmpty()) { "At least one Toobit contract is required" }
        synchronized(lock) {
            exchangeToJavad.clear()
            contracts.forEach { exchangeToJavad[it.exchangeSymbol] = it.javadSymbol }
            previousBySymbol.clear()
            fifteenMinuteBars.clear()
            last45MinuteBucketBySymbol.clear()
        }
        pingTask?.cancel(false)
        val sourceInterval = if (timeframe == AnalysisTimeframe.FORTY_FIVE_MINUTES) "15m"
            else timeframe.apiInterval

        return httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://stream.toobit.com/quote/ws/v1"), object : WebSocket.Listener {
                private val fragments = StringBuilder()

                override fun onOpen(webSocket: WebSocket) {
                    socket = webSocket
                    val request = mapOf(
                        "symbol" to contracts.joinToString(", ") { it.exchangeSymbol },
                        "topic" to "kline_$sourceInterval",
                        "event" to "sub",
                        "params" to mapOf("binary" to false)
                    )
                    webSocket.sendText(json.writeValueAsString(request), true)
                    pingTask = scheduler.scheduleAtFixedRate({
                        socket?.sendText(
                            json.writeValueAsString(mapOf("ping" to System.currentTimeMillis())),
                            true
                        )
                    }, 60, 60, TimeUnit.SECONDS)
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    fragments.append(data)
                    if (last) {
                        val message = fragments.toString()
                        fragments.setLength(0)
                        processMessage(message, timeframe)
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    socket = null
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                    socket = null
                    pingTask?.cancel(false)
                    return CompletableFuture.completedFuture(null)
                }
            }).thenAccept { webSocket -> socket = webSocket }
    }

    private fun processMessage(raw: String, timeframe: AnalysisTimeframe) {
        val root = try { json.readTree(raw) } catch (_: Exception) { return }
        val exchangeSymbol = root.path("symbol").asText("")
        val javadSymbol = synchronized(lock) { exchangeToJavad[exchangeSymbol] } ?: return
        val data = root.path("data")
        if (!data.isArray) return

        data.forEach { item ->
            val candle = MarketCandle(
                openTime = item.path("t").asLong(),
                open = item.path("o").asDouble(),
                high = item.path("h").asDouble(),
                low = item.path("l").asDouble(),
                close = item.path("c").asDouble(),
                volume = item.path("v").asDouble()
            )
            if (candle.openTime <= 0L ||
                !listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() } ||
                candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0 ||
                candle.volume < 0.0 || candle.high < maxOf(candle.open, candle.close) ||
                candle.low > minOf(candle.open, candle.close) || candle.high < candle.low
            ) return@forEach

            if (timeframe == AnalysisTimeframe.FORTY_FIVE_MINUTES) {
                processFortyFiveMinute(javadSymbol, candle)
            } else {
                processStandardCandle(javadSymbol, candle, timeframe)
            }
        }
    }

    private fun processStandardCandle(
        symbol: String,
        candle: MarketCandle,
        timeframe: AnalysisTimeframe
    ) {
        val updates = mutableListOf<ToobitCandleUpdate>()
        synchronized(lock) {
            val previous = previousBySymbol[symbol]
            when {
                previous == null -> {
                    previousBySymbol[symbol] = candle
                    updates += ToobitCandleUpdate(symbol, candle, false)
                }
                candle.openTime < previous.openTime -> return
                candle.openTime == previous.openTime -> {
                    previousBySymbol[symbol] = candle
                    updates += ToobitCandleUpdate(symbol, candle, false)
                }
                else -> {
                    val gap = candle.openTime - previous.openTime != intervalMillis(timeframe.apiInterval)
                    previousBySymbol[symbol] = candle
                    updates += ToobitCandleUpdate(symbol, previous, true, gap)
                    updates += ToobitCandleUpdate(symbol, candle, false, gap)
                }
            }
        }
        updates.forEach(onUpdate)
    }

    private fun processFortyFiveMinute(symbol: String, candle: MarketCandle) {
        val duration = 45L * 60_000L
        val sourceDuration = 15L * 60_000L
        val bucket = (candle.openTime / duration) * duration
        val updates = mutableListOf<ToobitCandleUpdate>()
        synchronized(lock) {
            val lastBucket = last45MinuteBucketBySymbol[symbol]
            if (lastBucket != null && bucket < lastBucket) return
            if (lastBucket == null || bucket > lastBucket) {
                last45MinuteBucketBySymbol[symbol] = bucket
            }

            val bars = fifteenMinuteBars.getOrPut(symbol) { mutableMapOf() }
            bars[candle.openTime] = candle

            val completedBuckets = bars.keys
                .map { (it / duration) * duration }
                .distinct()
                .filter { it < bucket }
                .sorted()
            completedBuckets.forEach { finishedBucket ->
                val finished = bars.values
                    .filter { (it.openTime / duration) * duration == finishedBucket }
                    .sortedBy { it.openTime }
                val complete = finished.size == 3 &&
                    finished.mapIndexed { index, item ->
                        item.openTime == finishedBucket + index * sourceDuration
                    }.all { it } &&
                    finished.last().openTime + sourceDuration == finishedBucket + duration
                if (complete) {
                    updates += ToobitCandleUpdate(
                        symbol, aggregateFortyFive(finishedBucket, finished), true
                    )
                } else {
                    updates += ToobitCandleUpdate(
                        symbol, aggregateFortyFive(finishedBucket, finished), true, true
                    )
                }
                bars.keys.removeIf { (it / duration) * duration == finishedBucket }
            }

            val currentBucketBars = bars.values
                .filter { (it.openTime / duration) * duration == bucket }
                .sortedBy { it.openTime }
            val completeCurrent = currentBucketBars.size == 3 &&
                currentBucketBars.mapIndexed { index, item ->
                    item.openTime == bucket + index * sourceDuration
                }.all { it } &&
                currentBucketBars.last().openTime + sourceDuration == bucket + duration
            if (completeCurrent) {
                updates += ToobitCandleUpdate(
                    symbol, aggregateFortyFive(bucket, currentBucketBars), false
                )
            }
        }
        updates.forEach(onUpdate)
    }

    private fun intervalMillis(interval: String): Long = when (interval) {
        "1m" -> 60_000L
        "5m" -> 5 * 60_000L
        "15m" -> 15 * 60_000L
        "30m" -> 30 * 60_000L
        "1h" -> 60 * 60_000L
        "2h" -> 2 * 60 * 60_000L
        else -> throw IllegalArgumentException("Unsupported Toobit stream interval: $interval")
    }

    private fun aggregateFortyFive(
        bucketStart: Long,
        candles: List<MarketCandle>
    ) = MarketCandle(
        openTime = bucketStart,
        open = candles.first().open,
        high = candles.maxOf { it.high },
        low = candles.minOf { it.low },
        close = candles.last().close,
        volume = candles.sumOf { it.volume }
    )

    override fun close() {
        pingTask?.cancel(false)
        scheduler.shutdownNow()
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Javad market stream stopped")
        socket = null
    }
}
