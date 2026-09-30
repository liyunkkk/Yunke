package io.github.mangi.eta.agent.device

import org.json.JSONObject

/** Failure finalization is not model work: run once, never replay, and preserve cancellation. */
internal object VirtualDisplayAbortCleanup {
    fun failureCode(cleanup: () -> JSONObject?): String? {
        // The cancelled model may leave this thread interrupted. Temporarily isolate just the
        // finalizer; a NEW interrupt still stops the normal owner preflight/release checks.
        val wasInterrupted = Thread.interrupted()
        try {
            val receipt = cleanup() ?: return null // This exact run never owned a virtual session.
            if (receipt.opt("ok") == true && receipt.opt("released") == true) return null
            return receipt.optString("error").takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                ?: "AUTO_CLEANUP_FAILED"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return "AUTO_CLEANUP_INTERRUPTED"
        } catch (_: Exception) {
            return "AUTO_CLEANUP_FAILED"
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }
}
