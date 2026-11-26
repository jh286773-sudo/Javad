package com.javad.signal

/**
 * A signal tied to the exact Toobit source interval and completed candle that
 * produced it. The candle close is available to the notification layer as the
 * signal-time price.
 */
data class TimeframeSignal(
    val symbol: String,
    val timeframe: AnalysisTimeframe,
    val candle: MarketCandle,
    val signal: Signal
)

/**
 * Coordinates selected-timeframe analysis for all selected contracts.
 *
 * The caller must subscribe ToobitKlineStream with settings.selectedTimeframe
 * and initialize each selected symbol with its complete available REST history
 * before forwarding stream updates. This preserves LuxAlgo's cumulative Auto
 * Threshold and chart bar_index. Open candles are ignored; only updates marked
 * closed by the stream are evaluated.
 *
 * Changing timeframe invalidates every processor: the caller must reload
 * complete history and reconnect the stream at the new interval before
 * analysis resumes. No candles from different intervals can be mixed.
 */
class SelectedTimeframeAnalysis(
    initialSettings: AnalysisSettings,
    private val autoThresholdEnabled: Boolean = true
) {
    private var settings: AnalysisSettings = initialSettings
    private val processors = mutableMapOf<String, LuxAlgoFvgAutoProcessor>()
    private val lastProcessedOpenTime = mutableMapOf<String, Long>()
    private val signalGates = PerSymbolDirectionSignalGate()

    @Synchronized
    fun currentSettings(): AnalysisSettings = settings

    /**
     * Applies the user's single selected timeframe to every selected contract.
     * A timeframe change clears all warmed-up state so the next interval cannot
     * inherit the previous interval's bar_index or cumulative Auto Threshold.
     */
    @Synchronized
    fun updateSettings(next: AnalysisSettings) {
        val timeframeChanged = next.selectedTimeframe != settings.selectedTimeframe
        val previousSymbols = settings.symbols
        val removedSymbols = previousSymbols - next.symbols
        settings = next

        if (timeframeChanged) {
            processors.clear()
            lastProcessedOpenTime.clear()
            (previousSymbols + next.symbols).forEach(signalGates::remove)
        } else {
            removedSymbols.forEach { symbol ->
                processors.remove(symbol)
                lastProcessedOpenTime.remove(symbol)
                signalGates.remove(symbol)
            }
        }
    }

    /**
     * Seeds one contract from its full, ascending, gap-free REST history.
     * Historical bars warm the LuxAlgo calculation but do not emit alerts.
     */
    @Synchronized
    fun loadHistory(symbol: String, candles: List<MarketCandle>) {
        val normalized = normalizeSelectedSymbol(symbol)
        require(candles.isNotEmpty()) { "Complete Toobit history is required before live analysis" }

        val processor = LuxAlgoFvgAutoProcessor(
            timeframe = settings.selectedTimeframe,
            autoThresholdEnabled = autoThresholdEnabled
        )
        processor.processHistory(candles)

        processors[normalized] = processor
        lastProcessedOpenTime[normalized] = candles.last().openTime
    }

    /**
     * Evaluates only a completed candle from the currently selected interval.
     * A detected gap invalidates the processor until REST backfill is loaded.
     */
    @Synchronized
    fun onCandleUpdate(update: ToobitCandleUpdate): TimeframeSignal? {
        val normalized = update.javadSymbol.trim().uppercase()
        if (normalized !in settings.symbols) return null

        check(update.timeframe == settings.selectedTimeframe) {
            "Received ${update.timeframe.apiInterval} candle while ${settings.selectedTimeframe.apiInterval} is selected"
        }
        if (!update.isClosed) return null

        if (update.gapDetected) {
            invalidate(normalized)
            throw IllegalStateException(
                "Toobit candle gap for $normalized; backfill history before resuming analysis"
            )
        }

        val processor = processors[normalized]
            ?: throw IllegalStateException(
                "Load complete ${settings.selectedTimeframe.apiInterval} history for $normalized before live analysis"
            )
        val lastTime = lastProcessedOpenTime[normalized]
        if (lastTime != null && update.candle.openTime <= lastTime) return null

        val evaluation = try {
            processor.process(update.candle)
        } catch (error: IllegalArgumentException) {
            invalidate(normalized)
            throw IllegalStateException(
                "Candle sequence for $normalized is incomplete; reload history before resuming analysis",
                error
            )
        }
        lastProcessedOpenTime[normalized] = update.candle.openTime

        val signal = signalGates.accept(normalized, evaluation) ?: return null
        return TimeframeSignal(
            symbol = normalized,
            timeframe = settings.selectedTimeframe,
            candle = update.candle,
            signal = signal
        )
    }

    @Synchronized
    fun isHistoryLoaded(symbol: String): Boolean =
        processors.containsKey(symbol.trim().uppercase())

    private fun normalizeSelectedSymbol(symbol: String): String {
        val normalized = symbol.trim().uppercase()
        require(normalized in settings.symbols) {
            "Contract $normalized is not selected for analysis"
        }
        return normalized
    }

    private fun invalidate(symbol: String) {
        processors.remove(symbol)
        lastProcessedOpenTime.remove(symbol)
        signalGates.remove(symbol)
    }
}
