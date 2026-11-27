package com.javad.signal

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

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
    private var reconciliationExecutor: ScheduledExecutorService? = null
    private var reconciliationTask: ScheduledFuture<*>? = null
    private var activeContracts: List<ToobitPerpetualContract> = emptyList()
    private var activeTimeframe: AnalysisTimeframe? = null

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
            val latestCompletedBySymbol = mutableMapOf<String, MarketCandle>()
            contracts.forEach { contract ->
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = contract.javadSymbol,
                    timeframe = settings.selectedTimeframe,
                    endTimeMillis = endTime
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no completed history for ${contract.javadSymbol}"
                }
                // Every start rebuilds the full available history from its
                // earliest candle. The final candle seeds the live stream
                // cursor so startup omissions are detected immediately.
                analysis.loadHistory(contract.javadSymbol, history)
                latestCompletedBySymbol[contract.javadSymbol] = history.last()
            }

            activeContracts = contracts.toList()
            activeTimeframe = settings.selectedTimeframe
            val newStream = ToobitKlineStream(onUpdate = ::handleUpdate)
            stream = newStream
            val connection = newStream.connect(
                contracts = contracts,
                timeframe = settings.selectedTimeframe,
                latestCompletedCandles = latestCompletedBySymbol
            )
            startPeriodicReconciliation(settings.selectedTimeframe)
            return connection.whenComplete { _, error ->
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
                // The stream emits the prior candle as closed and then the
                // current candle as preview. Recover on the preview only:
                // REST history will include the just-closed candle, avoiding
                // two backfills for the same detected gap.
                if (update.isClosed) return
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
                    history
                )
                analysis.onCandleUpdate(update.copy(gapDetected = false))?.let(onSignal)
                return
            }

            analysis.onCandleUpdate(update)?.let(onSignal)
        } catch (error: Throwable) {
            onError("Signal analysis paused for ${update.javadSymbol}; recovery is required", error)
        }
    }

    /**
     * REST reconciliation is a safety net for silent WebSocket omissions and
     * exchange-side history-boundary changes. If Toobit exposes an older first
     * candle, all derived HA/index/cumulative state is rebuilt from that candle.
     */
    @Synchronized
    private fun startPeriodicReconciliation(timeframe: AnalysisTimeframe) {
        val executor = Executors.newSingleThreadScheduledExecutor(ThreadFactory { task ->
            Thread(task, "javad-history-reconciliation").apply { isDaemon = true }
        })
        reconciliationExecutor = executor
        val period = timeframe.durationMillis.coerceAtLeast(60_000L)
        reconciliationTask = executor.scheduleAtFixedRate(
            { reconcileAllHistories() },
            period,
            period,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun reconcileAllHistories() {
        if (stream == null) return
        val timeframe = activeTimeframe ?: return
        activeContracts.forEach { contract ->
            try {
                val endTime = marketData.getServerTimeMillis()
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = contract.javadSymbol,
                    timeframe = timeframe,
                    endTimeMillis = endTime
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no reconciliation history for ${contract.javadSymbol}"
                }
                // Compare every candle, not only the first/last timestamps.
                // Internal gaps or revised OHLC values also invalidate HA,
                // bar_index and cumulative Auto Threshold state.
                if (!analysis.historyMatches(contract.javadSymbol, history)) {
                    analysis.loadHistory(
                        contract.javadSymbol,
                        history
                    )
                }
            } catch (error: Throwable) {
                onError(
                    "History reconciliation failed for ${contract.javadSymbol}; live state was not replaced",
                    error
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        stopStream()
    }

    private fun stopStream() {
        reconciliationTask?.cancel(false)
        reconciliationTask = null
        reconciliationExecutor?.shutdownNow()
        reconciliationExecutor = null
        activeContracts = emptyList()
        activeTimeframe = null
        val active = stream
        stream = null
        active?.close()
    }

    override fun close() = stop()
}
