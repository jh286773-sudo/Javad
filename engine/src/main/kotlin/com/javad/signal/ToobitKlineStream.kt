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
    val gapDetected: Boolean = false,
    /** The exact exchange interval used by this subscription. */
    val timeframe: AnalysisTimeframe
)

/**
 * Public Toobit USDT-M kline stream. Subscribe only after loading history so
 * the caller can continue the LuxAlgo cumulative calculation chronologically.
 * Unexpected disconnects are retried with bounded exponential backoff. The
 * caller must still backfill missed candles before resuming signal evaluation.
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
    @Volatile private var desiredContracts: List<ToobitPerpetualContract> = emptyList()
    @Volatile private var desiredTimeframe: AnalysisTimeframe? = null
    @Volatile private var manuallyClosed = false
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var reconnectTask: ScheduledFuture<*>? = null
    private val previousBySymbol = mutableMapOf<String, MarketCandle>()
    private val exchangeToJavad = mutableMapOf<String, String>()
    private val lock = Any()

    fun connect(
        contracts: List<ToobitPerpetualContract>,
        timeframe: AnalysisTimeframe,
        latestCompletedCandles: Map<String, MarketCandle> = emptyMap()
    ): CompletableFuture<Void> {
        require(contracts.isNotEmpty()) { "At least one Toobit contract is required" }
        synchronized(lock) {
            desiredContracts = contracts.toList()
            desiredTimeframe = timeframe
            manuallyClosed = false
            reconnectAttempt = 0
            reconnectScheduled = false
            reconnectTask?.cancel(false)
            reconnectTask = null
            exchangeToJavad.clear()
            contracts.forEach { contract ->
                exchangeToJavad[contract.exchangeSymbol] = contract.javadSymbol
            }
            // Seed the stream cursor with the final REST-committed candle.
            // The first live update can then be checked for a gap instead of
            // being accepted blindly as the first observed candle.
            previousBySymbol.clear()
            contracts.forEach { contract ->
                latestCompletedCandles[contract.javadSymbol]?.let {
                    previousBySymbol[contract.javadSymbol] = it
                }
            }
        }
        pingTask?.cancel(false)
        val connection = openSocket(contracts, timeframe)
        return connection
    }

    private fun openSocket(
        contracts: List<ToobitPerpetualContract>,
        timeframe: AnalysisTimeframe
    ): CompletableFuture<Void> {
        val sourceInterval = timeframe.apiInterval

        val connection = httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://stream.toobit.com/quote/ws/v1"), object : WebSocket.Listener {
                private val fragments = StringBuilder()

                override fun onOpen(webSocket: WebSocket) {
                    socket = webSocket
                    synchronized(lock) {
                        reconnectAttempt = 0
                        reconnectScheduled = false
                        reconnectTask = null
                    }
                    pingTask?.cancel(false)
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
                    if (socket === webSocket) {
                        socket = null
                        pingTask?.cancel(false)
                        scheduleReconnect()
                    }
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                    if (socket === webSocket) {
                        socket = null
                        pingTask?.cancel(false)
                        scheduleReconnect()
                    }
                    return CompletableFuture.completedFuture(null)
                }
            })

        connection.whenComplete { _, error ->
            if (error != null) scheduleReconnect()
        }
        return connection.thenAccept { }
    }

    /** Reconnects with bounded exponential backoff after an unexpected disconnect. */
    private fun scheduleReconnect() {
        synchronized(lock) {
            if (manuallyClosed || desiredContracts.isEmpty() || desiredTimeframe == null || reconnectScheduled) {
                return
            }
            reconnectScheduled = true
            val delayMillis = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
            reconnectAttempt += 1
            reconnectTask = scheduler.schedule({
                val contracts: List<ToobitPerpetualContract>
                val timeframe: AnalysisTimeframe
                synchronized(lock) {
                    reconnectScheduled = false
                    reconnectTask = null
                    if (manuallyClosed) return@schedule
                    contracts = desiredContracts
                    timeframe = desiredTimeframe ?: return@schedule
                }
                openSocket(contracts, timeframe)
            }, delayMillis, TimeUnit.MILLISECONDS)
        }
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

            processStandardCandle(javadSymbol, candle, timeframe)
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
                    updates += ToobitCandleUpdate(symbol, candle, false, timeframe = timeframe)
                }
                candle.openTime < previous.openTime -> return
                candle.openTime == previous.openTime -> {
                    previousBySymbol[symbol] = candle
                    updates += ToobitCandleUpdate(symbol, candle, false, timeframe = timeframe)
                }
                else -> {
                    val gap = candle.openTime - previous.openTime != timeframe.durationMillis
                    previousBySymbol[symbol] = candle
                    updates += ToobitCandleUpdate(symbol, previous, true, gap, timeframe)
                    updates += ToobitCandleUpdate(symbol, candle, false, gap, timeframe)
                }
            }
        }
        updates.forEach(onUpdate)
    }


    override fun close() {
        synchronized(lock) {
            manuallyClosed = true
            reconnectScheduled = false
            reconnectTask?.cancel(false)
            reconnectTask = null
        }
        pingTask?.cancel(false)
        scheduler.shutdownNow()
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Javad market stream stopped")
        socket = null
    }
}
