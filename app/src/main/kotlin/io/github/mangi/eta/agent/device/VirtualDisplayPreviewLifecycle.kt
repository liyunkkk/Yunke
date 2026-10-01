package io.github.mangi.eta.agent.device

/** Serializes restore/open/revoke so a stale startup read cannot resurrect revoked authority. */
internal class VirtualDisplayPreviewLifecycle(
    private val store: Store,
    private val create: (VirtualDisplayPreviewHttpServer.Ticket?, Boolean) -> VirtualDisplayPreviewHttpServer,
) {
    interface Store {
        fun read(): VirtualDisplayPreviewHttpServer.Ticket?
        fun write(ticket: VirtualDisplayPreviewHttpServer.Ticket)
        fun delete()
    }
    private var server: VirtualDisplayPreviewHttpServer? = null
    private var ticket: VirtualDisplayPreviewHttpServer.Ticket? = null
    private var revoked = false

    // Keep revocation available for live authority and when disk state cannot be proved absent.
    @Synchronized fun hasPairing(): Boolean = ticket != null ||
        runCatching { store.read() != null }.getOrDefault(true)

    class PersistenceCleanupException(cause: Exception) :
        IllegalStateException("Preview pairing cleanup failed", cause)
    @Synchronized fun isRunning(): Boolean = server?.isRunning() == true

    @Synchronized fun restore() {
        if (revoked || isRunning()) return
        val saved = store.read() ?: return
        stopListener()
        start(saved, saved.controlToken != null, persist = false)
    }

    @Synchronized fun open(control: Boolean): VirtualDisplayPreviewHttpServer.Ticket {
        val current = ticket
        if (isRunning() && current != null && (current.controlToken != null) == control) return current
        val saved = store.read()
        stopListener()
        val resume = saved?.takeIf { (it.controlToken != null) == control }
        // Permission changes revoke the entire old capability, never upgrade its read token.
        if (saved != null && resume == null) store.delete()
        return start(resume, control, persist = true).also { revoked = false }
    }

    @Synchronized fun revoke() {
        revoked = true
        stopListener()
        store.delete() // Failure is visible; remain offline even if deletion failed.
    }

    private fun start(saved: VirtualDisplayPreviewHttpServer.Ticket?, control: Boolean,
        persist: Boolean): VirtualDisplayPreviewHttpServer.Ticket {
        val next = create(saved, control)
        var saveAttempted = false
        try {
            val value = next.start()
            if (persist) {
                saveAttempted = true
                store.write(value)
            }
            server = next
            ticket = value
            return value
        } catch (ex: Exception) {
            next.stop()
            if (saveAttempted) {
                // write() can fail after an atomic commit. Never restore a failed open later.
                revoked = true
                try { store.delete() } catch (cleanup: Exception) {
                    cleanup.addSuppressed(ex)
                    throw PersistenceCleanupException(cleanup)
                }
            }
            throw ex
        }
    }

    private fun stopListener() {
        server?.stop()
        server = null
        ticket = null
    }
}
