package io.github.mangi.eta.ui.app

/** UI-only expiry; never deletes coordinator results, resumable context or worktrees. */
internal class TimedOutChildVisibility(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val deadlines = mutableMapOf<Pair<String, String>, Long>()

    fun observe(runId: String, taskId: String, status: String): Boolean {
        val key = runId to taskId
        if (status !in HIDDEN_AFTER_DELAY) {
            deadlines.remove(key)
            return true
        }
        val deadline = deadlines.getOrPut(key) { now() + HIDE_AFTER_MS }
        return now() < deadline
    }

    fun schedulesHide(status: String): Boolean = status in HIDDEN_AFTER_DELAY

    fun remaining(runId: String, taskId: String): Long =
        ((deadlines[runId to taskId] ?: now()) - now()).coerceAtLeast(0)

    companion object {
        const val HIDE_AFTER_MS = 30_000L
        val HIDDEN_AFTER_DELAY = setOf("completed", "cancelled", "timed_out", "failed")
    }
}
