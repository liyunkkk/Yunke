package io.github.mangi.eta.agent.vivo

/** Main-thread state machine; no reconnect or retry of work with an unknown outcome. */
internal class VivoClientState(private val limit: Int = 256) {
    data class Ticket(val id: String, val generation: Long)
    private val seen = HashSet<String>()
    private var generation = 0L
    private var active: Ticket? = null
    private var sent = false
    private var closed = false

    fun begin(id: String): Ticket? {
        if (closed || active != null || id in seen || seen.size >= limit) return null
        seen.add(id)
        return Ticket(id, ++generation).also { active = it; sent = false }
    }
    fun current(ticket: Ticket) = !closed && active == ticket
    fun markSent(ticket: Ticket, stillOwner: Boolean = true): Boolean {
        if (!stillOwner || !current(ticket) || sent) return false
        sent = true
        return true
    }
    fun finish(ticket: Ticket): Boolean {
        if (!current(ticket)) return false
        active = null
        return true
    }
    fun close() { closed = true; active = null }
}
