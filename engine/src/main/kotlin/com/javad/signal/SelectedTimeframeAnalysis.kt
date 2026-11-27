package com.javad.signal

/**
 * A LuxAlgo FVG + Auto signal confirmed only after its source candle closes.
 * Same-direction repeats are suppressed until an opposite-direction signal
 * is confirmed on a later closed candle.
 */
data class TimeframeSignal(
    val symbol: String,
    val timeframe: AnalysisTimeframe,
    val candle: MarketCandle,
    val signal: Signal,
    val fvgState: FvgStateUpdate
)

/**
 * Coordinates selected-timeframe analysis for all selected contracts.
 *
 * The caller subscribes ToobitKlineStream with settings.selectedTimeframe and
 * seeds each selected symbol with ascending, gap-free Toobit history. This
 * selected interval is the LuxAlgo chart timeframe; FVG uses Chart mode
 * (`fvg_tf = ""`), never an independent FVG interval.
 *
 * Every forming-candle update may preview the raw FVG condition and update
 * the displayed preview state using Pine rollback semantics. Preview updates
 * never emit a signal and never commit ta.cum, bar_index, or FVG arrays.
 * A LONG/SHORT signal is evaluated for delivery only from the final closed
 * candle snapshot, after that snapshot commits the source calculation state.
 * Repeated signals in the same direction are suppressed until the opposite
 * direction is confirmed on a later closed candle.
 *
 * Numbering follows Pine's chart dataset: the earliest candle returned by
 * Toobit's history request is bar_index 0; each subsequent candle gets the
 * next integer. ta.cum starts at zero on that same first candle. The numbering
 * and cumulative state are independent for each contract/timeframe.
 * TradingView numerical parity still requires identical history boundaries
 * and OHLC data; different exchange feeds cannot be assumed to share those.
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
    private val latestFvgStates = mutableMapOf<String, FvgStateUpdate>()
    private val lastEmittedSignalOpenTime = mutableMapOf<String, Long>()
    private val lastEmittedDirection = mutableMapOf<String, FvgDirection>()

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
            latestFvgStates.clear()
            lastEmittedSignalOpenTime.clear()
            lastEmittedDirection.clear()
        } else {
            removedSymbols.forEach { symbol ->
                processors.remove(symbol)
                lastProcessedOpenTime.remove(symbol)
                latestFvgStates.remove(symbol)
                lastEmittedSignalOpenTime.remove(symbol)
                lastEmittedDirection.remove(symbol)
            }
        }
    }

    /**
     * Seeds one contract from ascending, gap-free Toobit history.
     *
     * The first supplied (earliest) candle is always bar_index 0 and the
     * cumulative absolute-delta sum starts at zero, matching Pine's numbering
     * from the beginning of its loaded chart dataset. Historical bars warm the
     * calculation but do not emit alerts.
     */
    @Synchronized
    fun loadHistory(
        symbol: String,
        candles: List<MarketCandle>
    ) {
        val normalized = normalizeSelectedSymbol(symbol)
        require(candles.isNotEmpty()) { "Complete Toobit history is required before live analysis" }

        val processor = LuxAlgoFvgAutoProcessor(
            chartTimeframe = settings.selectedTimeframe,
            autoThresholdEnabled = autoThresholdEnabled
        )
        val historyEvaluations = processor.processHistory(candles)

        processors[normalized] = processor
        lastProcessedOpenTime[normalized] = candles.last().openTime
        lastEmittedSignalOpenTime.remove(normalized)
        lastEmittedDirection.remove(normalized)
        historyEvaluations.lastOrNull()?.fvgState?.let { latestFvgStates[normalized] = it }
    }

    /**
     * Returns the latest FVG box-array state for a selected contract.
     * During a forming candle this reflects the current preview (Pine rollback);
     * after close it reflects the committed source arrays.
     */
    @Synchronized
    fun currentFvgState(symbol: String): FvgStateUpdate? =
        latestFvgStates[symbol.trim().uppercase()]

    /**
     * Evaluates every live snapshot against the raw LuxAlgo condition and
     * commits only when the stream marks that candle closed. A detected gap
     * invalidates the processor until REST backfill is loaded.
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

        evaluation.fvgState?.let { latestFvgStates[normalized] = it }
        if (update.isClosed) {
            lastProcessedOpenTime[normalized] = update.candle.openTime
        }

        // Forming candles are preview-only. Even if the raw LuxAlgo
        // conditions become true intrabar, no signal is emitted before close.
        if (!update.isClosed) return null
        if (evaluation.bullishFvg == evaluation.bearishFvg) return null
        if (lastEmittedSignalOpenTime[normalized] == update.candle.openTime) return null
        val delta = evaluation.deltaPercent ?: return null
        val threshold = evaluation.autoThreshold ?: return null
        val direction = if (evaluation.bullishFvg) FvgDirection.LONG else FvgDirection.SHORT
        if (lastEmittedDirection[normalized] == direction) return null

        val signal = Signal(
            direction = direction,
            chartBarIndex = evaluation.chartBarIndex,
            deltaPercent = delta,
            autoThreshold = threshold
        )
        lastEmittedSignalOpenTime[normalized] = update.candle.openTime
        lastEmittedDirection[normalized] = direction
        return TimeframeSignal(
            symbol = normalized,
            timeframe = settings.selectedTimeframe,
            candle = update.candle,
            signal = signal,
            fvgState = evaluation.fvgState ?: FvgStateUpdate(emptyList(), emptyList(), emptyList())
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
        latestFvgStates.remove(symbol)
        lastEmittedSignalOpenTime.remove(symbol)
        lastEmittedDirection.remove(symbol)
    }
}
