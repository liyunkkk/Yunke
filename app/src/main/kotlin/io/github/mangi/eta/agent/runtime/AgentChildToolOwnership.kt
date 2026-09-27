package io.github.mangi.eta.agent.runtime

/** Parent cancellation/finally are the SAME reference; the child retains its own reference.
 * Register and terminal cancellation can race with the parent worker's finally block. */
internal class AgentChildToolOwnership(private val closeTools: () -> Unit) {
    private var parentHeld = true
    private var childHeld = false
    private var closed = false

    @Synchronized fun retain(): Boolean {
        if (closed || childHeld) return false
        childHeld = true
        return true
    }

    /** Called from parent controller cleanup OR parent worker finally; idempotent. */
    fun release() {
        val shouldClose = synchronized(this) {
            if (childScope.get() == true) childHeld = false else parentHeld = false
            if (!parentHeld && !childHeld && !closed) { closed = true; true } else false
        }
        if (shouldClose) closeTools()
    }

    companion object {
        private val childScope = ThreadLocal<Boolean>()
        /** Wraps the release callback handed to a task group; not a parent-controller callback. */
        fun releaseChild(block: () -> Unit) {
            val previous = childScope.get()
            childScope.set(true)
            try { block() } finally { childScope.set(previous) }
        }
    }
}
