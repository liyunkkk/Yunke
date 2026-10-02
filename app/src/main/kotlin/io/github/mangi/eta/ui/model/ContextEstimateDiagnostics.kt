package io.github.mangi.eta.ui.model

import io.github.mangi.eta.core.AppFileLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Numeric-only evidence, independent of toolDiagnosticAttempt. At most one line per captured run. */
internal class ContextEstimateDiagnostics(
    private val enabled: () -> Boolean = { AppFileLogger.isEnabled() },
    private val sink: (String) -> Unit = { AppFileLogger.info(it) },
) {
    enum class Basis(val wireValue: String) {
        RECEIPT_DELTA("receipt_delta"), LOCAL_FALLBACK("local_fallback"),
    }

    data class Snapshot(
        val basis: Basis,
        val localEstimateTokens: Int,
        val overheadTokensEst: Int,
        val historyTokensEst: Int,
        val overheadCalibrationTokens: Int,
        val calibrationSamples: Int,
    )

    private val pending = LinkedHashMap<String, Snapshot>()

    @Synchronized
    fun capture(runId: String, snapshot: Snapshot) {
        if (!enabled()) return
        pending[runId] = snapshot
        // Bound even detached runs whose terminal event never returns to this UI process.
        while (pending.size > MAX_PENDING_RUNS) pending.remove(pending.keys.first())
    }

    @Synchronized
    fun receipt(runId: String, cloudInput: Int, cloudCached: Int?, learned: Boolean, newOffset: Int?) {
        val snapshot = pending.remove(runId) ?: return
        if (!enabled()) return
        val fields = buildJsonObject {
            put("basis", snapshot.basis.wireValue)
            put("local_estimate_tokens", snapshot.localEstimateTokens)
            put("overhead_tokens_est", snapshot.overheadTokensEst)
            put("history_tokens_est", snapshot.historyTokensEst)
            put("overhead_calibration_tokens", snapshot.overheadCalibrationTokens)
            put("calibration_samples", snapshot.calibrationSamples)
            put("cloud_input", cloudInput)
            cloudCached?.let { put("cloud_cached", it) }
            put("estimate_error", cloudInput.toLong() - snapshot.localEstimateTokens)
            put("calibration_learned", learned)
            put("new_offset", newOffset ?: 0)
        }
        // Diagnostics must not alter receipt delivery if a sink fails.
        runCatching { if (enabled()) sink(PREFIX + fields.toString()) }
    }

    @Synchronized
    fun clear(runId: String) { pending.remove(runId) }

    companion object {
        const val PREFIX = "context_estimate "
        private const val MAX_PENDING_RUNS = 32
    }
}
