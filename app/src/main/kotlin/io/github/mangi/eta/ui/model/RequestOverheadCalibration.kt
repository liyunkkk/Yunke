package io.github.mangi.eta.ui.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Display-only empirical ratios in raw request units, never an additive fixed overhead.
 * Provider/model and configuration scope are checked by the caller/store. Raw overhead and
 * history share are conservative composition proxies, NOT proof of identical tokenization. */
internal object RequestOverheadCalibration {
    private const val WINDOW = 3

    fun routeSignature(provider: io.github.mangi.eta.data.model.ProviderSetting,
        model: io.github.mangi.eta.data.model.Model): String {
        // Arbitrary headers/body/gateway JSON can contain credentials and change routing. Fail closed
        // rather than persisting their contents or silently sharing a calibration across changes.
        if (provider.customHeaders.isNotEmpty() || provider.customBody.isNotEmpty() ||
            model.customHeaders.isNotEmpty() || model.customBody.isNotEmpty() || provider.sessionGatewayJson.isNotBlank()) return ""
        val uri = runCatching { java.net.URI(provider.baseUrl) }.getOrNull() ?: return ""
        if (uri.userInfo != null || uri.query != null) return ""
        val endpoint = when (provider) {
            is io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting -> provider.endpointMode
            is io.github.mangi.eta.data.model.CustomProviderSetting -> provider.endpointMode
            is io.github.mangi.eta.data.model.AnthropicProviderSetting -> provider.anthropicVersion
        }
        val fields = listOf(provider.id, provider.baseUrl, provider.sourceType, endpoint,
            provider.systemPrompt, provider.authMode, provider.responsesStripReasoningStatus,
            provider.hostedWebSearchEnabled, model.copy(createdAt = 0, displayName = "", sortOrder = 0))
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(fields.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Serializable
    data class Observation(val requestId: String, val cloudInput: Int, val history: Int, val overhead: Int) {
        val ratio: Double get() = cloudInput.toDouble() / (history.toLong() + overhead)
    }

    @Serializable
    data class Sample(val observations: List<Observation>, val routeSignature: String = "") {
        val samples: Int get() = observations.size
        val ratio: Double? get() {
            if (observations.size != WINDOW || observations.map { it.requestId }.distinct().size != WINDOW) return null
            val ratios = observations.map { it.ratio }
            if (ratios.any { !it.isFinite() || it !in 0.5..2.0 } || ratios.max() / ratios.min() > 1.10) return null
            return ratios.average()
        }
        fun estimate(history: Int, overhead: Int): Int? {
            val stable = ratio ?: return null
            if (observations.any { !compatible(it.history, it.overhead, history, overhead) }) return null
            return ((history.toLong() + overhead) * stable).coerceIn(0.0, Int.MAX_VALUE.toDouble()).roundToInt()
        }
    }

    fun compatible(oldHistory: Int, oldOverhead: Int, history: Int, overhead: Int): Boolean {
        if (history < 0 || overhead <= 0 || oldHistory < 0 || oldOverhead <= 0) return false
        // Runtime injects small request-specific overhead (observed 25237 UI vs 25270 request).
        // Allow at most 2% / 512 raw tokens, not unrelated tool/config compositions.
        if (kotlin.math.abs(oldOverhead.toLong() - overhead) > minOf(512.0, oldOverhead * 0.02)) return false
        val oldTotal = oldHistory.toLong() + oldOverhead
        val total = history.toLong() + overhead
        return total.toDouble() / oldTotal in 0.75..1.25 &&
            kotlin.math.abs(overhead.toDouble() / total - oldOverhead.toDouble() / oldTotal) <= 0.05
    }

    fun learn(previous: Sample?, cloudInput: Int, requestHistoryTokens: Int,
        requestOverheadTokens: Int, inflatedCache: Boolean = false,
        requestId: String, routeSignature: String = ""): Sample? {
        if (inflatedCache || routeSignature.isBlank() || requestId.isBlank() || cloudInput <= 0 || requestHistoryTokens < 0 || requestOverheadTokens <= 0) return null
        val observation = Observation(requestId, cloudInput, requestHistoryTokens, requestOverheadTokens)
        if (!observation.ratio.isFinite() || observation.ratio !in 0.5..2.0) return null
        val scoped = previous?.takeIf { it.routeSignature == routeSignature }
        if (scoped?.observations?.any { it.requestId == requestId } == true) return null
        // Always retain the latest requests, including volatility; never cherry-pick stable history.
        return Sample((scoped?.observations.orEmpty() + observation).takeLast(WINDOW), routeSignature)
    }

    /** A same-session last receipt may degrade to an estimate, within the same composition range. */
    fun receiptEstimate(cloudInput: Int, oldHistory: Int, oldOverhead: Int, history: Int, overhead: Int): Int? {
        if (cloudInput <= 0 || !compatible(oldHistory, oldOverhead, history, overhead)) return null
        val ratio = cloudInput.toDouble() / (oldHistory.toLong() + oldOverhead)
        if (ratio !in 0.5..2.0) return null
        return ((history.toLong() + overhead) * ratio).coerceIn(0.0, Int.MAX_VALUE.toDouble()).roundToInt()
    }
}

/** Unmeasured display state is independent of the conservative internal send budget. */
internal data class ContextDisplayPolicy(val firstTurn: Boolean = false, val awaitingReceipt: Boolean = false,
    val receiptEstimateTokens: Int? = null)
