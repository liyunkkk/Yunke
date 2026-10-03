package io.github.mangi.eta.ui.model

import io.github.mangi.eta.core.AppFileLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Numeric-only request evidence. A run is not a request: mismatches deliberately have no error.
 * 显示学习已删除：这里只配对“发送前快照”与本次真实回执，不记录任何 learned/sample/ratio 字段。 */
internal class ContextEstimateDiagnostics(
    private val enabled: () -> Boolean = { AppFileLogger.isEnabled() },
    private val sink: (String) -> Unit = { AppFileLogger.info(it) },
) {
    enum class Basis(val wireValue: String) {
        RECEIPT_DELTA("receipt_delta"), LOCAL_FALLBACK("local_fallback"),
    }
    enum class UiState { NONE, UNKNOWN, ACTUAL }
    data class Snapshot(
        val basis: Basis,
        val localEstimateTokens: Int,
        val overheadTokensEst: Int,
        val historyTokensEst: Int,
        val round: Int = 1,
        val contextEpoch: Int = 0,
        val uiState: UiState = UiState.UNKNOWN,
        val uiTokens: Int? = null,
    )
    private val pending = LinkedHashMap<String, Snapshot>()
    @Synchronized fun capture(runId: String, snapshot: Snapshot) {
        if (!enabled()) return
        pending[runId] = snapshot
        while (pending.size > 32) pending.remove(pending.keys.first())
    }
    @Synchronized fun receipt(runId: String, cloudInput: Int, cloudCached: Int?, round: Int? = null,
        historyTokens: Int? = null, overheadTokens: Int? = null, contextEpoch: Int = 0) {
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
            put("cloud_input", cloudInput)
            cloudCached?.let { put("cloud_cached", it) }
            if (matched) put("estimate_error", cloudInput.toLong() - snapshot.localEstimateTokens)
        }
        runCatching { if (enabled()) sink(PREFIX + fields.toString()) }
    }
    @Synchronized fun clear(runId: String) { pending.remove(runId) }
    companion object { const val PREFIX = "context_estimate " }
}
