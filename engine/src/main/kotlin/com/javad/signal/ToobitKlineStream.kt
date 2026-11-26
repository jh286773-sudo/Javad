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
import java.util.concurrent.TimeUnit

data class ToobitCandleUpdate(
    val javadSymbol: String,
    val candle: MarketCandle,
    /** True only after the exchange starts publishing the next source candle. */
    val isClosed: Boolean
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
    private val previousBySymbol = mutableMapOf<String, MarketCandle>()
    private val exchangeToJavad = mutableMapOf<String, String>()
    private val lock = Any()

    fun connect(
        contracts: List<ToobitPerpetualContract>,
        timeframe: AnalysisTimeframe
    ): CompletableFuture<Void> {
        require(contracts.isNotEmpty()) { "At least one Toobit contract is required" }
        synchronized(lock) {
            exchangeToJavad.clear()
            contracts.forEach { exchangeToJavad[it.exchangeSymbol] = it.javadSymbol }
            previousBySymbol.clear()
        }
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
                    scheduler.scheduleAtFixedRate({
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
            if (timeframe == AnalysisTimeframe.FORTY_FIVE_MINUTES) {
                processFortyFiveMinute(javadSymbol, candle)
            } else {
                val previous = synchronized(lock) { previousBySymbol.put(javadSymbol, candle) }
                if (previous != null && previous.openTime != candle.openTime) {
                    onUpdate(ToobitCandleUpdate(javadSymbol, previous, true))
                }
                onUpdate(ToobitCandleUpdate(javadSymbol, candle, false))
            }
        }
    }

    private val fifteenMinuteBars = mutableMapOf<String, MutableMap<Long, MarketCandle>>()

    private fun processFortyFiveMinute(symbol: String, candle: MarketCandle) {
        val duration = 45L * 60_000L
        val bucket = (candle.openTime / duration) * duration
        synchronized(lock) {
            val bars = fifteenMinuteBars.getOrPut(symbol) { mutableMapOf() }
            bars[candle.openTime] = candle
            val bucketBars = bars.values.filter { (it.openTime / duration) * duration == bucket }
                .sortedBy { it.openTime }
            if (bucketBars.size == 3) {
                onUpdate(ToobitCandleUpdate(symbol, aggregateFortyFive(bucket, bucketBars), false))
            }

            val completedBuckets = bars.keys.map { (it / duration) * duration }.distinct().sorted()
            if (completedBuckets.size > 1) {
                val finishedBucket = completedBuckets[completedBuckets.lastIndex - 1]
                val finished = bars.values.filter { (it.openTime / duration) * duration == finishedBucket }
                    .sortedBy { it.openTime }
                if (finished.size == 3) {
                    onUpdate(ToobitCandleUpdate(symbol, aggregateFortyFive(finishedBucket, finished), true))
                }
                bars.keys.removeIf { (it / duration) * duration <= finishedBucket }
            }
        }
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
        scheduler.shutdownNow()
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Javad market stream stopped")
        socket = null
    }
}
