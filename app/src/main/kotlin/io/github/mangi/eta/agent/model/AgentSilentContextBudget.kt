package io.github.mangi.eta.agent.model

/** Decision-only calibration. Never emitted as a cloud bill or ordinary ring usage. */
internal class AgentSilentContextBudget {
    private var requestLocal: Int = 0
    private var measuredInput: Int? = null
    private var measuredLocal: Int = 0

    /**
     * True while [measuredInput] came from [seed], i.e. the previous run's receipt
     * projected onto this request, rather than from a receipt of this run.
     *
     * A seed is an estimate, so it must not serve as the baseline of the growth check
     * in [isPlausible]. Observed on a 272k window: the seed was ~153k, the first real
     * receipt of the new run was 209964 (cache miss, local growth ~0). The +57k step
     * exceeded the ~21.8k slack and was refused; every later receipt (88%, 92%, 95%)
     * was then compared with the same stale seed and refused too, so [cloudInput]
     * stayed null and automatic compaction never ran while the ring showed 95%.
     */
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

    fun requestStarted(localTokens: Int) { requestLocal = localTokens.coerceAtLeast(0) }

    /**
     * Anchors on a cloud measurement, but only when it can actually describe the
     * prompt that was just sent.
     *
     * Some gateways sum a retried or multi-leg request into one usage object: on the
     * wire we saw `input_tokens = 784267` for a request that succeeded on a 500000
     * window, and a later round billing 267917 right after 38880 while the local
     * transcript had grown by ~1100. Anchoring on such a number makes every later
     * decision believe the context is nearly full, which is how auto-compaction fired
     * far below its threshold and then immediately compacted a second time.
     *
     * Three one-directional refusals:
     *  - the value cannot exceed the window by an unbounded factor;
     *  - its cache read cannot exceed its own total or the window (see
     *    [AgentBilledPromptPlausibility.isInflatedCacheRead]); such a bill is dropped
     *    outright, nothing is updated and no scaling is applied;
     *  - its step above the previous anchor cannot far exceed the local growth since
     *    that anchor.
     * The absolute ratio between a bill and the local estimate is deliberately *not*
     * checked: a first bill can legitimately be several times the local count, so that
     * test would reject correct receipts.
     *
     * Rejecting an outlier keeps the previous anchor (or the local boundary), which is
     * conservative in the safe direction: a genuine overflow still surfaces as a
     * provider CONTEXT_WINDOW_EXCEEDED failure rather than as a silently wrong anchor.
     */
    fun measured(inputTokens: Int?, contextWindow: Int? = null, cachedTokens: Int? = null) {
        if (inputTokens == null || inputTokens <= 0) return
        if (!isPlausible(inputTokens, cachedTokens, contextWindow)) return
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
    fun cloudStale() { cloudInput = null }

    private fun learnScale(inputTokens: Int) {
        if (requestLocal < MIN_SCALE_BASIS) return
        val observed = inputTokens.toDouble() / requestLocal
        if (observed <= 1.0) return
        underCountScale = maxOf(underCountScale, observed.coerceAtMost(MAX_SCALE))
    }

    private fun isPlausible(
        inputTokens: Int,
        cachedTokens: Int?,
        contextWindow: Int?
    ): Boolean {
        val window = contextWindow?.takeIf { it > 0 }
        if (!AgentBilledPromptPlausibility.fitsWindow(inputTokens, window)) return false
        // A cache read larger than its own prompt or the window is a relay billing artefact.
        if (AgentBilledPromptPlausibility.isInflatedCacheRead(inputTokens, cachedTokens, window)) {
            return false
        }
        // A seeded estimate is not a receipt: the first bill of a run has no baseline.
        if (anchorIsSeed) return true
        val previous = measuredInput?.takeIf { it > 0 } ?: return true
        val billedGrowth = inputTokens.toLong() - previous
        if (billedGrowth <= 0) return true
        val localGrowth = (requestLocal.toLong() - measuredLocal).coerceAtLeast(0L)
        val slack = GROWTH_SLACK_TOKENS.toLong() +
            (window?.toLong() ?: 0L) * GROWTH_SLACK_WINDOW_PERCENT / 100
        return billedGrowth <= localGrowth + slack
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
        /** Absolute slack for cache accounting and per-round request scaffolding. */
        const val GROWTH_SLACK_TOKENS = 8_192

        /** Extra slack proportional to the window, for large-context models. */
        const val GROWTH_SLACK_WINDOW_PERCENT = 5

        /** Below this a ratio is dominated by fixed per-request overhead. */
        const val MIN_SCALE_BASIS = 2_000

        /** Never let one odd receipt inflate the correction without bound. */
        const val MAX_SCALE = 2.0
    }
}
