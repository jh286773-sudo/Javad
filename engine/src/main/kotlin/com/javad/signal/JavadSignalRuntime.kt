package com.javad.signal

import java.util.concurrent.CompletableFuture

/**
 * Public entry point for the Javad signal engine.
 *
 * The Android application should own one runtime instance for its monitoring
 * lifecycle, call start() from a background executor, forward emitted signals
 * to its notification layer, and call stop() only when the user turns analysis
 * off. No TradingView connection, API key, or order-placement API is involved.
 */
class JavadSignalRuntime(
    private val onSignal: (TimeframeSignal) -> Unit,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    private val marketData: ToobitMarketDataClient = ToobitMarketDataClient()
) : AutoCloseable {
    private val analysis = SelectedTimeframeAnalysis(AnalysisSettings())
    private val monitor = ToobitSignalMonitor(
        marketData = marketData,
        analysis = analysis,
        onSignal = onSignal,
        onError = onError
    )

    /**
     * Loads each selected contract's exact configured rolling Toobit history window, initializes
     * its independent LuxAlgo-style FVG + Auto state, then starts live updates.
     * Invoke off the Android main thread because history retrieval is blocking.
     */
    fun start(settings: AnalysisSettings): CompletableFuture<Void> =
        monitor.start(settings)

    /** Stop market monitoring when the user explicitly disables analysis. */
    fun stop() = monitor.stop()

    override fun close() = stop()
}
