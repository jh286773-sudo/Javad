package com.javad.signal

import java.util.concurrent.CompletableFuture

/**
 * Connects the existing Toobit market-data and FVG/Auto engine into one
 * read-only monitoring lifecycle.
 *
 * Call start() from a background thread: it downloads all completed Toobit
 * history available for each selected USDT perpetual, warms
 * each symbol's independent Pine-style bar_index/ta.cum state, and only then
 * opens the live kline stream.
 *
 * On a detected stream gap, the affected symbol is reloaded from the same
 * earliest-available history anchor before live analysis resumes. This avoids continuing
 * with a shifted bar index or an incomplete cumulative Auto Threshold.
 */
class ToobitSignalMonitor(
    private val marketData: ToobitMarketDataClient,
    private val analysis: SelectedTimeframeAnalysis,
    private val onSignal: (TimeframeSignal) -> Unit,
    private val onError: (String, Throwable) -> Unit = { _, _ -> }
) : AutoCloseable {
    @Volatile
    private var stream: ToobitKlineStream? = null

    @Synchronized
    fun start(settings: AnalysisSettings): CompletableFuture<Void> {
        require(settings.symbols.isNotEmpty()) { "Select at least one Toobit perpetual contract" }
        stopStream()
        analysis.updateSettings(settings)

        try {
            val selected = settings.symbols
            val contracts = marketData.listUsdtPerpetualContracts()
                .filter { it.javadSymbol in selected }
            val missing = selected - contracts.map { it.javadSymbol }.toSet()
            require(missing.isEmpty()) {
                "Unsupported or inactive Toobit USDT perpetual contracts: ${missing.sorted().joinToString()}"
            }

            // Use exchange time so device-clock drift cannot truncate the history window.
            val endTime = marketData.getServerTimeMillis()
            contracts.forEach { contract ->
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = contract.javadSymbol,
                    timeframe = settings.selectedTimeframe,
                    endTimeMillis = endTime
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no completed history for ${contract.javadSymbol}"
                }
                analysis.loadHistory(contract.javadSymbol, history)
            }

            val newStream = ToobitKlineStream(onUpdate = ::handleUpdate)
            stream = newStream
            return newStream.connect(contracts, settings.selectedTimeframe)
                .whenComplete { _, error ->
                    if (error != null) onError("Toobit WebSocket connection failed", error)
                }
        } catch (error: Throwable) {
            stopStream()
            onError("Could not start Toobit signal monitoring", error)
            throw error
        }
    }

    /**
     * A gap invalidates the old cumulative state. Reload the affected symbol
     * first; a closed gap-boundary candle is already present in that history,
     * while a still-forming candle is evaluated again as a live preview.
     */
    @Synchronized
    private fun handleUpdate(update: ToobitCandleUpdate) {
        try {
            if (update.gapDetected) {
                val endTime = marketData.getServerTimeMillis()
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = update.javadSymbol,
                    timeframe = update.timeframe,
                    endTimeMillis = endTime
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no recovery history for ${update.javadSymbol}"
                }
                analysis.loadHistory(
                    update.javadSymbol,
                    history,
                    preserveLastSignalCandle = true
                )
                if (update.isClosed) return
                analysis.onCandleUpdate(update.copy(gapDetected = false))?.let(onSignal)
                return
            }

            analysis.onCandleUpdate(update)?.let(onSignal)
        } catch (error: Throwable) {
            onError("Signal analysis paused for ${update.javadSymbol}; recovery is required", error)
        }
    }

    @Synchronized
    fun stop() {
        stopStream()
    }

    private fun stopStream() {
        val active = stream
        stream = null
        active?.close()
    }

    override fun close() = stop()
}
