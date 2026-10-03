package io.github.mangi.eta.ui.model

import java.security.MessageDigest
import org.json.JSONObject

/** Checkpoint context state exists even without a receipt (not derived from paginated messages). */
internal object CloudUsageReceiptCodec {
    data class Receipt(val inputTokens: Int, val historyTokens: Int?, val overheadTokens: Int?,
        val routeSignature: String? = null, val requestId: String? = null)
    data class DisplayState(val hasStarted: Boolean, val awaitingReceipt: Boolean)

    fun encode(conversationId: String, providerId: String, modelId: String, history: String, inputTokens: Int?,
        historyTokens: Int? = null, overheadTokens: Int? = null,
        hasStarted: Boolean = false, awaitingReceipt: Boolean = false, routeSignature: String? = null,
        requestId: String? = null): String {
        val actual = inputTokens != null && inputTokens > 0 && !awaitingReceipt
        return JSONObject().put("version", 1).put("source", if (actual) "cloud_actual" else "context_state")
            .put("conversation", conversationId).put("provider", providerId).put("model", modelId)
            .put("history", fingerprint(history)).put("has_started", hasStarted || actual)
            .put("awaiting_receipt", awaitingReceipt)
            .apply {
                if (actual) {
                    put("input", inputTokens)
                    historyTokens?.takeIf { it >= 0 }?.let { put("request_history_tokens", it) }
                    overheadTokens?.takeIf { it >= 0 }?.let { put("request_overhead_tokens", it) }
                    routeSignature?.let { put("route_signature", it) }
                    requestId?.takeIf { it.isNotBlank() }?.let { put("request_id", it) }
                }
            }.toString()
    }

    fun decodeDisplayState(raw: String, conversationId: String, history: String): DisplayState? = runCatching {
        val value = JSONObject(raw)
        if (value.optInt("version") != 1 || value.optString("conversation") != conversationId ||
            value.optString("history") != fingerprint(history)) null
        else DisplayState(value.optBoolean("has_started", true), value.optBoolean("awaiting_receipt", false))
    }.getOrNull()

    fun decode(raw: String, conversationId: String, providerId: String, modelId: String, history: String): Int? =
        decodeReceipt(raw, conversationId, providerId, modelId, history)?.inputTokens

    fun decodeReceipt(raw: String, conversationId: String, providerId: String, modelId: String, history: String): Receipt? =
        runCatching {
            val receipt = JSONObject(raw)
            if (receipt.optInt("version") != 1 || receipt.optString("source") != "cloud_actual" ||
                receipt.optBoolean("awaiting_receipt", false) ||
                receipt.optString("conversation") != conversationId || receipt.optString("provider") != providerId ||
                receipt.optString("model") != modelId || receipt.optString("history") != fingerprint(history)) null
            else receipt.optInt("input").takeIf { it > 0 }?.let {
                Receipt(it, receipt.optInt("request_history_tokens", -1).takeIf { n -> n >= 0 },
                    receipt.optInt("request_overhead_tokens", -1).takeIf { n -> n >= 0 },
                    receipt.optString("route_signature").takeIf { value -> value.isNotBlank() },
                    receipt.optString("request_id").takeIf { value -> value.isNotBlank() })
            }
        }.getOrNull()

    private fun fingerprint(history: String): String = MessageDigest.getInstance("SHA-256")
        .digest(history.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
