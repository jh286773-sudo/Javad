package com.javad.signal

/**
 * A signal tied to the exact Toobit source interval and candle snapshot that
 * first satisfied the FVG condition. The snapshot may be intrabar; its close
 * field is the latest traded price in that update.
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
 * The caller subscribes ToobitKlineStream with settings.selectedTimeframe and
 * seeds each selected symbol with ascending, gap-free Toobit history. This
 * selected interval is the LuxAlgo chart timeframe; FVG uses Chart mode
 * (`fvg_tf = ""`), never an independent FVG interval.
 *
 * Every update to the forming chart candle is evaluated intrabar. Preview
 * evaluations use Pine realtime rollback semantics: they calculate from the
 * last committed ta.cum value and do not advance bar_index. The final candle
 * snapshot is committed exactly once when Toobit reports it closed.
 *
 * The history anchor parameters on loadHistory allow the caller to align
 * bar_index and ta.cum with a known Pine chart-history starting point. If no
 * external chart anchor is supplied, the first Toobit candle is bar_index 0
 * and the cumulative sum starts at zero. Exact TradingView parity therefore
 * requires identical chart history and OHLC data, not merely the same interval.
 *
 * Changing timeframe invalidates every processor. The caller must reload
 * history and reconnect the stream at the new interval before analysis resumes.
 */
class SelectedTimeframeAnalysis(
    initialSettings: AnalysisSettings,
    private val autoThresholdEnabled: Boolean = true
) {
    private var settings: AnalysisSettings = initialSettings
    private val processors = mutableMapOf<String, LuxAlgoFvgAutoProcessor>()
    private val lastProcessedOpenTime = mutableMapOf<String, Long>()
    private val emittedSignalOpenTime = mutableMapOf<String, Long>()

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
            emittedSignalOpenTime.clear()
        } else {
            removedSymbols.forEach { symbol ->
                processors.remove(symbol)
                lastProcessedOpenTime.remove(symbol)
                emittedSignalOpenTime.remove(symbol)
            }
        }
    }

    /**
     * Seeds one contract from ascending, gap-free Toobit history.
     *
     * firstChartBarIndex and initialCumulativeAbsoluteDelta are the Pine
     * history anchor: bar_index at the first supplied candle and ta.cum's
     * absolute-delta sum immediately before that candle. Leave them at zero
     * when the supplied history is the beginning of the chart dataset.
     * Historical bars warm the calculation but do not emit alerts.
     */
    @Synchronized
    fun loadHistory(
        symbol: String,
        candles: List<MarketCandle>,
        firstChartBarIndex: Long = 0L,
        initialCumulativeAbsoluteDelta: Double = 0.0
    ) {
        val normalized = normalizeSelectedSymbol(symbol)
        require(candles.isNotEmpty()) { "Complete Toobit history is required before live analysis" }

        val processor = LuxAlgoFvgAutoProcessor(
            chartTimeframe = settings.selectedTimeframe,
            autoThresholdEnabled = autoThresholdEnabled,
            firstChartBarIndex = firstChartBarIndex,
            initialCumulativeAbsoluteDelta = initialCumulativeAbsoluteDelta
        )
        processor.processHistory(candles)

        processors[normalized] = processor
        lastProcessedOpenTime[normalized] = candles.last().openTime
        emittedSignalOpenTime.remove(normalized)
    }

    /**
     * Evaluates each live snapshot intrabar and commits the final snapshot when
     * the stream marks that candle closed. A detected gap invalidates the
     * processor until REST backfill is loaded.
     */
    @Synchronized
    fun onCandleUpdate(update: ToobitCandleUpdate): TimeframeSignal? {
        val normalized = update.javadSymbol.trim().uppercase()
        if (normalized !in settings.symbols) return null

        check(update.timeframe == settings.selectedTimeframe) {
            "Received ${update.timeframe.apiInterval} candle while ${settings.selectedTimeframe.apiInterval} is selected"
        }
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
            if (update.isClosed) {
                processor.process(update.candle)
            } else {
                processor.preview(update.candle)
            }
        } catch (error: IllegalArgumentException) {
            invalidate(normalized)
            throw IllegalStateException(
                "Candle sequence for $normalized is incomplete; reload history before resuming analysis",
                error
            )
        }

        if (update.isClosed) {
            lastProcessedOpenTime[normalized] = update.candle.openTime
        }

        // A qualifying intrabar event is latched once per candle, matching a
        // once-per-bar alert even if the live condition later rolls back.
        if (emittedSignalOpenTime[normalized] == update.candle.openTime) return null
        if (evaluation.bullishFvg == evaluation.bearishFvg) return null
        val delta = evaluation.deltaPercent ?: return null
        val threshold = evaluation.autoThreshold ?: return null
        val signal = Signal(
            direction = if (evaluation.bullishFvg) FvgDirection.LONG else FvgDirection.SHORT,
            chartBarIndex = evaluation.chartBarIndex,
            deltaPercent = delta,
            autoThreshold = threshold
        )
        emittedSignalOpenTime[normalized] = update.candle.openTime
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
        emittedSignalOpenTime.remove(symbol)
    }
}
