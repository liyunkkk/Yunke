package io.github.mangi.eta.agent.device

/** Bounded retries of an authenticated, read-only focus preflight rejection. No device access. */
internal object VirtualDisplayHandoffRetry {
    const val ERROR_CODE = "HANDOFF_PREFLIGHT_FAILED"
    const val MAX_ATTEMPTS = 3
    private val DELAYS_MILLIS = longArrayOf(300L, 700L)

    // This is the owner's existing wire detail, not proof by itself. In particular, an anchor
    // launch can fail with empty tallies AFTER the first side effect. Never normalize this match.
    private val PREFLIGHT_DETAIL = Regex("preflight:(display|selection|inventory|focus|cancelled):[A-Za-z0-9_$]+; ?moved=\\[\\] removed=\\[\\]")
    private val FOCUS_DETAIL = Regex("preflight:focus:[A-Za-z0-9_$]+; ?moved=\\[\\] removed=\\[\\]")

    data class OwnerIdentity(val socketName: String, val pid: Long, val displayId: Int, val uniqueId: String) {
        fun isValid(): Boolean = socketName.isNotBlank() && pid > 0 && displayId > 0 && uniqueId.isNotBlank()
    }

    /** Only populated from a fresh status on the same peer-authenticated owner connection. */
    data class OwnerState(
        val identity: OwnerIdentity,
        val authenticated: Boolean,
        val flags: VirtualDisplayRecoveryPolicy.Flags,
        val retainedTaskIds: Set<Int>,
        val sourceTaskCount: Int,
        /** Null when status omitted liveTaskIds. Never treat omission as an empty inventory. */
        val liveTaskIds: Set<Int>? = null,
        /** Null when status omitted goneTaskIds. Never treat omission as an empty inventory. */
        val goneTaskIds: Set<Int>? = null,
    )

    sealed interface Attempt {
        object Completed : Attempt
        // Safe diagnostics are not authentication. Callers must explicitly supply transport proof.
        data class Refused(val code: String, val detail: String, val authenticated: Boolean = false) : Attempt
    }

    data class Failure(val code: String, val detail: String)

    sealed interface Outcome {
        object HandedOff : Outcome

        /** Clean means a matching fresh owner status was read AFTER the final rejection too. */
        data class Stopped(val failure: Failure?, val cleanPreflight: Boolean = false) : Outcome {
            val phase: String get() = if (cleanPreflight) "handoff_pending" else "uncertain"
        }
    }

    /** Shared by finish/onRunClosed in one round. Only clean exhaustion may reset next round. */
    class Budget(maxAttempts: Int = MAX_ATTEMPTS) {
        private val limit = maxAttempts.coerceIn(0, MAX_ATTEMPTS)
        private var used = 0
        var blocked: Boolean = false
            private set
        val remaining: Int get() = if (blocked) 0 else (limit - used).coerceAtLeast(0)
        fun hasRemaining(): Boolean = remaining > 0
        fun consume() { if (hasRemaining()) used++ }
        fun reset() { if (!blocked) used = 0 }

        // A clean non-focus refusal stops this round too, without claiming mutation uncertainty.
        fun exhaust() { used = limit }

        // A later adoption must not turn a possibly applied mutation into another handoff.
        fun stop() { blocked = true; exhaust() }
    }

    /** Syntax only: neither empty tallies nor the error code alone authorize replay. */
    fun isRetryable(code: String, detail: String): Boolean =
        code == ERROR_CODE && FOCUS_DETAIL.matches(detail)

    /** Read-only does not imply retryable: only the focus preflight is retried automatically. */
    private fun isCleanPreflight(code: String, detail: String): Boolean =
        code == ERROR_CODE && PREFLIGHT_DETAIL.matches(detail)

    /**
     * Compare the complete recovery-relevant state, not just a subset of retained tasks. Frame
     * counters can legitimately advance and are not mutation evidence. The authenticated failure
     * code supplies the no-side-effects signal (including no anchor launch); status corroborates
     * the same owner and unchanged clean state. Neither signal is sufficient on its own.
     *
     * Gone tasks may make sourceTaskCount smaller than retainedTaskIds. Escaped tasks, an unknown
     * live/gone inventory, or a frozen id that is no longer live still fail closed.
     */
    fun freshStateAllowsRetry(before: OwnerState?, after: OwnerState?, frozenSelectedIds: Set<Int>): Boolean {
        if (!sameAuthenticatedOwner(before, after)) return false
        if (frozenSelectedIds.isEmpty() || frozenSelectedIds.any { it <= 0 }) return false
        val current = after ?: return false
        val f = current.flags
        if (f.finishing || f.handoffComplete || f.releaseAttempted || f.mutationUncertain || f.sourceEmpty) return false
        if (!inventoryAllowsMutation(current)) return false
        val live = current.liveTaskIds ?: return false
        return live.containsAll(frozenSelectedIds)
    }

    /**
     * Empty-selection cleanup. sourceEmpty may be either value; escaped or unknown inventory may not.
     * Does not relax non-empty delivery checks in [freshStateAllowsRetry].
     */
    fun freshStateAllowsCleanup(before: OwnerState?, after: OwnerState?): Boolean {
        if (!sameAuthenticatedOwner(before, after)) return false
        val current = after ?: return false
        val f = current.flags
        if (f.finishing || f.handoffComplete || f.releaseAttempted || f.mutationUncertain) return false
        return inventoryAllowsMutation(current)
    }

    /**
     * Keep must not freeze a task that status has already shown is not live.
     * A missing live inventory returns null so the caller keeps its retained-only check.
     */
    fun nonLiveKeepCode(liveTaskIds: Set<Int>?, wanted: Set<Int>): String? =
        if (liveTaskIds != null && !liveTaskIds.containsAll(wanted)) "TASK_NOT_LIVE" else null

    /**
     * A frozen delivery set that already contains a gone or escaped id is not rewritten.
     * Unknown live/gone returns null; the caller then fails closed as owner state unknown.
     */
    fun frozenDeliveryCode(
        liveTaskIds: Set<Int>?,
        goneTaskIds: Set<Int>?,
        retainedTaskIds: Set<Int>,
        frozen: Set<Int>,
    ): String? {
        if (frozen.isEmpty() || liveTaskIds == null || goneTaskIds == null) return null
        val escaped = retainedTaskIds - liveTaskIds - goneTaskIds
        return if (frozen.any { it in goneTaskIds || it in escaped }) "DELIVERY_TASK_GONE" else null
    }

    private fun sameAuthenticatedOwner(before: OwnerState?, after: OwnerState?): Boolean {
        if (before == null || after == null || !before.authenticated || !after.authenticated) return false
        if (!before.identity.isValid()) return false
        return before == after
    }

    /** live/gone known, disjoint retained subsets, nothing escaped, and the count covers every live root. */
    private fun inventoryAllowsMutation(state: OwnerState): Boolean {
        if (state.retainedTaskIds.any { it <= 0 } || state.sourceTaskCount < 0) return false
        if (state.flags.sourceEmpty != (state.sourceTaskCount == 0)) return false
        val live = state.liveTaskIds ?: return false
        val gone = state.goneTaskIds ?: return false
        if (live.any { it <= 0 } || gone.any { it <= 0 }) return false
        if (!state.retainedTaskIds.containsAll(live) || !state.retainedTaskIds.containsAll(gone)) return false
        if (live.intersect(gone).isNotEmpty()) return false
        if ((state.retainedTaskIds - live - gone).isNotEmpty()) return false
        return state.sourceTaskCount >= live.size
    }

    fun delayForRetry(retryIndex: Int): Long =
        DELAYS_MILLIS[retryIndex.coerceIn(0, DELAYS_MILLIS.lastIndex)]

    /**
     * verifyFresh proves unchanged authenticated state after each clean refusal, even the last
     * allowed attempt. Before a retry it runs AFTER the delay, so stale evidence cannot authorize
     * the next handoff. An exhausted call never executes a handoff; its session retains diagnostics
     * and independently revalidates evidence before retaining handoff_pending on reentry.
     */
    fun run(
        budget: Budget,
        handoff: () -> Attempt,
        verifyFresh: () -> Boolean,
        delay: (Long) -> Unit,
    ): Outcome {
        var first: Failure? = null
        var retryIndex = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) { budget.stop(); return Outcome.Stopped(first) }
            if (!budget.hasRemaining()) return Outcome.Stopped(first)
            budget.consume()
            val attempt = try {
                handoff()
            } catch (_: InterruptedException) {
                budget.stop()
                Thread.currentThread().interrupt()
                return Outcome.Stopped(Failure("HANDOFF_UNCERTAIN", ""))
            } catch (_: Exception) {
                budget.stop()
                return Outcome.Stopped(Failure("HANDOFF_UNCERTAIN", ""))
            }
            if (Thread.currentThread().isInterrupted) {
                budget.stop(); return Outcome.Stopped(Failure("HANDOFF_UNCERTAIN", ""))
            }
            when (attempt) {
                Attempt.Completed -> {
                    budget.stop() // A completed mutation is never another automatic handoff.
                    return Outcome.HandedOff
                }
                is Attempt.Refused -> {
                    if (first == null) first = Failure(attempt.code, attempt.detail)
                    if (!attempt.authenticated || !isCleanPreflight(attempt.code, attempt.detail)) {
                        budget.stop()
                        return Outcome.Stopped(Failure(attempt.code, attempt.detail))
                    }
                    val retryable = isRetryable(attempt.code, attempt.detail)
                    if (retryable && budget.hasRemaining() && !sleep(delay, delayForRetry(retryIndex))) {
                        budget.stop(); return Outcome.Stopped(first)
                    }
                    // Exhaustion or a non-focus refusal alone cannot prove a clean stop.
                    if (!verify(verifyFresh)) { budget.stop(); return Outcome.Stopped(first) }
                    if (Thread.currentThread().isInterrupted) { budget.stop(); return Outcome.Stopped(first) }
                    if (!retryable) {
                        budget.exhaust() // Prevent finish/onRunClosed from replaying this round.
                        return Outcome.Stopped(Failure(attempt.code, attempt.detail), cleanPreflight = true)
                    }
                    if (!budget.hasRemaining()) return Outcome.Stopped(first, cleanPreflight = true)
                    retryIndex++
                }
            }
        }
    }

    private fun verify(verifyFresh: () -> Boolean): Boolean =
        try {
            verifyFresh()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (_: Exception) {
            false
        }

    private fun sleep(delay: (Long) -> Unit, millis: Long): Boolean =
        try {
            delay(millis); true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (_: Exception) {
            false
        }
}
