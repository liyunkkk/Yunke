package io.github.mangi.eta.agent.model

/** Fixed receipt outcomes. Names and numbers only; not a display string. */
internal enum class SilentReceiptDecision {
    ACCEPTED,
    REJECTED_NON_POSITIVE,
    REJECTED_OVER_WINDOW,
    REJECTED_INFLATED_CACHE,
}

/** Decision-only calibration. Never emitted as a cloud bill or ordinary ring usage. */
internal class AgentSilentContextBudget {
    private var requestLocal: Int = 0
    private var measuredInput: Int? = null
    private var measuredLocal: Int = 0

    /** A previous-run seed calibrates the send guard, but is never a target receipt. */
    private var anchorIsSeed: Boolean = false

    /**
     * Ratio between what the provider billed and what the local heuristic counted for
     * the same request, learned from accepted receipts and deliberately kept across
     * [contextReplaced].
     *
     * The local count is a character heuristic (CJK 1.5/char, latin 0.25/char). On this
     * device's own traffic it measured billed/local ≈ 0.83..0.87 for ordinary rounds, so
     * it usually *over*-counts; but the ratio is content-dependent and punctuation-dense
     * code tokenizes far worse than 4 chars/token. When the ratio shows the heuristic
     * under-counting, an uncalibrated request configured for 200k can really leave at
     * ~220k, because the send limit compares against that under-count.
     *
     * Only values above 1 are retained, and only for the send limit: correcting a
     * known under-count is safe, while trusting an over-count would shrink the window
     * for no reason.
     */
    private var underCountScale: Double = 1.0

    /**
     * 本次 run 最近一张被接受的真实云端回执，也就是会话圆环上显示的那个数。自动压缩
     * （80%）只看它：没有回执、摘要替换了上下文、或工具修剪改了上下文之后都为 null，
     * 等下一张回执再决定。上一轮带来的种子只校准发送上限，不算回执。
     */
    private var cloudInput: Int? = null
    private var requestGeneration: Int = 0
    var lastReceiptDecision: SilentReceiptDecision? = null
        private set
    private var lastLoggedDecision: SilentReceiptDecision? = null
    private var lastLoggedAtNanos: Long = 0L

    fun requestStarted(localTokens: Int) {
        requestLocal = localTokens.coerceAtLeast(0)
        if (requestGeneration == Int.MAX_VALUE) requestGeneration = 0
        requestGeneration++
    }

    /** True only after this run accepted a receipt. A previous-run seed is not a target receipt. */
    fun hasTargetReceipt(): Boolean = measuredInput != null && !anchorIsSeed

    /**
     * Accept a positive, plausible receipt of the current request immediately. The caller
     * owns request attribution and merges partial fields only within that request.
     *
     * Keep the provider/window checks: unbounded totals and cache reads exceeding their
     * own total or the window cannot describe occupancy. Refusing them preserves the
     * previous anchor and does not teach a send-limit scale.
     *
     * Do not compare cloud growth with a local character estimate: cache accounting,
     * images and tokenization can change the bill without comparable local growth.
     * In particular, 193223 -> 230402 on a 272000 window must update cloudTokens now,
     * not require another provider request to confirm crossing the 80% boundary.
     */
    fun measured(inputTokens: Int?, contextWindow: Int? = null, cachedTokens: Int? = null) {
        if (inputTokens == null || inputTokens <= 0) {
            record(SilentReceiptDecision.REJECTED_NON_POSITIVE, 0)
            return
        }
        val window = contextWindow?.takeIf { it > 0 }
        if (!AgentBilledPromptPlausibility.fitsWindow(inputTokens, window)) {
            record(SilentReceiptDecision.REJECTED_OVER_WINDOW, inputTokens)
            return
        }
        if (AgentBilledPromptPlausibility.isInflatedCacheRead(inputTokens, cachedTokens, window)) {
            record(SilentReceiptDecision.REJECTED_INFLATED_CACHE, inputTokens)
            return
        }
        accept(inputTokens)
        record(SilentReceiptDecision.ACCEPTED, inputTokens)
    }

    private fun accept(inputTokens: Int) {
        measuredInput = inputTokens
        measuredLocal = requestLocal
        anchorIsSeed = false
        cloudInput = inputTokens
        learnScale(inputTokens)
    }

    /**
     * 用会话上一张回执折算到本次请求的值做发送上限的起点。只影响 [tokens] 和
     * [sendLimitTokens]，不产生 [cloudTokens]，也不学习倍率。
     */
    fun seed(localTokens: Int, inputTokens: Int?, contextWindow: Int? = null) {
        if (inputTokens == null || inputTokens <= 0) return
        requestStarted(localTokens)
        if (!AgentBilledPromptPlausibility.fitsWindow(inputTokens, contextWindow?.takeIf { it > 0 })) return
        measuredInput = inputTokens
        measuredLocal = requestLocal
        anchorIsSeed = true
    }

    /** 自动压缩用的云端实测；见 [cloudInput]。 */
    fun cloudTokens(): Int? = cloudInput

    /** 上下文被工具修剪改过：旧回执不再代表下一次请求，等新回执。发送上限的锚点保留。 */
    fun cloudStale() {
        cloudInput = null
    }

    private fun learnScale(inputTokens: Int) {
        if (requestLocal < MIN_SCALE_BASIS) return
        val observed = inputTokens.toDouble() / requestLocal
        if (observed <= 1.0) return
        underCountScale = maxOf(underCountScale, observed.coerceAtMost(MAX_SCALE))
    }

    private fun record(decision: SilentReceiptDecision, inputTokens: Int) {
        lastReceiptDecision = decision
        val now = System.nanoTime()
        if (decision == lastLoggedDecision && now - lastLoggedAtNanos < 2_000_000_000L) return
        lastLoggedDecision = decision
        lastLoggedAtNanos = now
        val anchor = measuredInput ?: -1
        runCatching {
            io.github.mangi.eta.core.AndroidAgentLogger.info(
                "silent_budget decision=${decision.name} input=$inputTokens anchor=$anchor gen=$requestGeneration",
            )
        }
    }

    fun tokens(currentLocal: Int): Int {
        val input = measuredInput ?: return currentLocal.coerceAtLeast(0)
        return (input.toLong() + currentLocal - measuredLocal)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Same value as [tokens], but with a known under-count corrected.
     *
     * Used only by the hard send limit. While a cloud anchor exists the anchor already
     * carries the provider's own number, so this returns [tokens] unchanged; the
     * correction matters exactly in the uncalibrated window right after a context
     * replacement, which is where an under-counted prompt used to slip out above the
     * configured limit. With no receipt ever observed the scale stays 1.0 and the
     * behaviour is bit-for-bit the previous one.
     */
    fun sendLimitTokens(currentLocal: Int): Int {
        val base = tokens(currentLocal)
        if (measuredInput != null || underCountScale <= 1.0) return base
        return (base * underCountScale).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
    }

    /**
     * False while the only basis is the local character heuristic. Callers that must
     * not act on a purely local estimate check this first.
     */
    fun isCalibrated(): Boolean = measuredInput != null

    fun contextReplaced() {
        measuredInput = null
        measuredLocal = 0
        requestLocal = 0
        anchorIsSeed = false
        cloudInput = null
        // underCountScale is a property of the model's tokenizer, not of this context.
    }

    private companion object {
        /** Below this a ratio is dominated by fixed per-request overhead. */
        const val MIN_SCALE_BASIS = 2_000

        /** Never let one odd receipt inflate the correction without bound. */
        const val MAX_SCALE = 2.0
    }
}
