package com.javad.signal

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Connects the Toobit market-data client and the FVG/Auto engine into one
 * read-only monitoring lifecycle.
 *
 * Call start() from a background thread: for each selected perpetual and
 * timeframe, it fetches the latest closed Toobit candle, calculates the
 * adaptive history size from the remaining slots to next UTC midnight, loads
 * the exact older history, and only then opens the live kline stream.
 *
 * A detected stream gap or UTC-day rollover reloads the affected adaptive
 * history window and rebuilds its dataset origin before live analysis resumes.
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
    private var dailyWindowRefreshTask: ScheduledFuture<*>? = null
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
                "Unsupported or inactive Toobit USDT perpetual contracts: " +
                    missing.sorted().joinToString()
            }

            // The loader reads Toobit's server clock and discovers the latest
            // closed candle separately for each selected contract.
            val latestCompletedBySymbol = mutableMapOf<String, MarketCandle>()
            contracts.forEach { contract ->
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = contract.javadSymbol,
                    timeframe = settings.selectedTimeframe
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no completed history for " + contract.javadSymbol
                }
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
                if (update.isClosed) return
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = update.javadSymbol,
                    timeframe = update.timeframe
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no recovery history for " + update.javadSymbol
                }
                analysis.loadHistory(update.javadSymbol, history)
                analysis.onCandleUpdate(update.copy(gapDetected = false))?.let(onSignal)
                return
            }

            analysis.onCandleUpdate(update)?.let(onSignal)
        } catch (error: Throwable) {
            onError("Signal analysis paused for " + update.javadSymbol + "; recovery is required", error)
        }
    }

    /**
     * REST reconciliation detects silent WebSocket omissions and exchange-side
     * revisions. A UTC-midnight refresh recomputes the adaptive left boundary.
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

        val serverNow = marketData.getServerTimeMillis()
        val nextUtcMidnight = TradingViewBasicHistoryWindow.nextUtcMidnightMillis(serverNow)
        val initialDailyDelay = (nextUtcMidnight - serverNow).coerceAtLeast(1L)
        val oneDayMillis = 24L * 60L * 60L * 1000L
        dailyWindowRefreshTask = executor.scheduleAtFixedRate(
            { reconcileAllHistories() },
            initialDailyDelay,
            oneDayMillis,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun reconcileAllHistories() {
        if (stream == null) return
        val timeframe = activeTimeframe ?: return
        activeContracts.forEach { contract ->
            try {
                val history = marketData.getAllHistoricalCandles(
                    javadSymbol = contract.javadSymbol,
                    timeframe = timeframe
                )
                require(history.isNotEmpty()) {
                    "Toobit returned no reconciliation history for " + contract.javadSymbol
                }
                if (!analysis.historyMatches(contract.javadSymbol, history)) {
                    analysis.loadHistory(contract.javadSymbol, history)
                }
            } catch (error: Throwable) {
                onError(
                    "History reconciliation failed for " + contract.javadSymbol +
                        "; live state was not replaced",
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
        dailyWindowRefreshTask?.cancel(false)
        dailyWindowRefreshTask = null
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
