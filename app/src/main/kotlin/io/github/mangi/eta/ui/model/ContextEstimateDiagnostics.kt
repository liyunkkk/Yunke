package io.github.mangi.eta.ui.model

import io.github.mangi.eta.core.AppFileLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Numeric-only request evidence. A run is not a request: mismatches deliberately have no error. */
internal class ContextEstimateDiagnostics(
    private val enabled: () -> Boolean = { AppFileLogger.isEnabled() },
    private val sink: (String) -> Unit = { AppFileLogger.info(it) },
) {
    enum class Basis(val wireValue: String) {
        RECEIPT_DELTA("receipt_delta"), LOCAL_FALLBACK("local_fallback"), LEARNED_RATIO("learned_ratio"),
    }
    enum class UiState { NONE, UNKNOWN, ACTUAL, ESTIMATE }
    data class Snapshot(
        val basis: Basis,
        val localEstimateTokens: Int,
        val overheadTokensEst: Int,
        val historyTokensEst: Int,
        val overheadCalibrationTokens: Int = 0, // v1 field retained only for source compatibility; never applied.
        val calibrationSamples: Int = 0,
        val round: Int = 1,
        val contextEpoch: Int = 0,
        val uiState: UiState = UiState.UNKNOWN,
        val uiTokens: Int? = null,
        val ratio: Double? = null,
    )
    private val pending = LinkedHashMap<String, Snapshot>()
    @Synchronized fun capture(runId: String, snapshot: Snapshot) {
        if (!enabled()) return
        pending[runId] = snapshot
        while (pending.size > 32) pending.remove(pending.keys.first())
    }
    @Synchronized fun receipt(runId: String, cloudInput: Int, cloudCached: Int?, learned: Boolean, newOffset: Int?,
        round: Int? = null, historyTokens: Int? = null, overheadTokens: Int? = null, contextEpoch: Int = 0,
        sampleCount: Int = 0, ratio: Double? = null) {
        val snapshot = pending.remove(runId) ?: return
        if (!enabled()) return
        val matched = round == snapshot.round && contextEpoch == snapshot.contextEpoch &&
            historyTokens == snapshot.historyTokensEst && overheadTokens == snapshot.overheadTokensEst
        val fields = buildJsonObject {
            put("basis", snapshot.basis.wireValue)
            put("pairing", if (matched) "matched_request" else "request_basis_mismatch")
            put("round", round ?: -1)
            put("context_epoch", contextEpoch)
            put("ui_state", snapshot.uiState.name.lowercase())
            snapshot.uiTokens?.let { put("ui_tokens", it) }
            put("local_estimate_tokens", snapshot.localEstimateTokens)
            put("overhead_tokens_est", snapshot.overheadTokensEst)
            put("history_tokens_est", snapshot.historyTokensEst)
            historyTokens?.let { put("request_history_tokens", it) }
            overheadTokens?.let { put("request_overhead_tokens", it) }
            put("calibration_samples", sampleCount)
            (ratio ?: snapshot.ratio)?.takeIf { it.isFinite() }?.let { put("calibration_ratio", it) }
            put("cloud_input", cloudInput)
            cloudCached?.let { put("cloud_cached", it) }
            if (matched) put("estimate_error", cloudInput.toLong() - snapshot.localEstimateTokens)
            put("calibration_learned", learned)
        }
        runCatching { if (enabled()) sink(PREFIX + fields.toString()) }
    }
    @Synchronized fun clear(runId: String) { pending.remove(runId) }
    companion object { const val PREFIX = "context_estimate " }
}
