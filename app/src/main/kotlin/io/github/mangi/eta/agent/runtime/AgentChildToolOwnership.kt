package io.github.mangi.eta.agent.runtime

/**
 * Cancellation and parent finally release the SAME parent reference. Each run-local
 * child group receives its own lease; frozen ordinary and current replacement groups
 * can coexist without closing each other's tool dependencies.
 */
internal class AgentChildToolOwnership(private val closeTools: () -> Unit) {
    private var parentHeld = true
    private val children = mutableSetOf<ChildLease>()
    private var closed = false

    @Synchronized fun retain(): ChildLease? {
        // Parent cancellation forbids NEW groups even while existing detached groups live.
        if (closed || !parentHeld) return null
        return ChildLease(this).also { children.add(it) }
    }

    /** Called from parent controller cleanup OR parent worker finally; idempotent. */
    fun release() {
        val shouldClose = synchronized(this) {
            parentHeld = false
            closeIfUnowned()
        }
        if (shouldClose) closeTools()
    }

    private fun releaseChild(lease: ChildLease) {
        val shouldClose = synchronized(this) {
            // Registration-stop and failed-construction cleanup can both own this callback.
            // Removing this exact lease only once cannot release another group's reference.
            if (!children.remove(lease)) return
            closeIfUnowned()
        }
        if (shouldClose) closeTools()
    }

    // Caller holds this owner's monitor; claim close before invoking arbitrary cleanup.
    private fun closeIfUnowned(): Boolean {
        if (parentHeld || children.isNotEmpty() || closed) return false
        closed = true
        return true
    }

    internal class ChildLease internal constructor(private val owner: AgentChildToolOwnership) : AutoCloseable {
        override fun close() = owner.releaseChild(this)
    }
}
