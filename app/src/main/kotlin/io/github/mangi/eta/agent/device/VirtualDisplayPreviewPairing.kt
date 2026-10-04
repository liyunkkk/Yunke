package io.github.mangi.eta.agent.device

/** Only preview capabilities, never owner IPC secrets. Stored in the app no-backup directory. */
internal object VirtualDisplayPreviewPairing {
    private val capability = Regex("[a-f0-9]{64}")
    const val MAX_BYTES = 512

    fun valid(ticket: VirtualDisplayPreviewHttpServer.Ticket): Boolean =
        ticket.port in 1024..65535 && ticket.port != 3070 && capability.matches(ticket.token) &&
            (ticket.controlToken == null ||
                (capability.matches(ticket.controlToken) && ticket.controlToken != ticket.token))

    fun encode(ticket: VirtualDisplayPreviewHttpServer.Ticket): String {
        require(valid(ticket)) { "Invalid preview pairing" }
        return "1\n${ticket.port}\n${ticket.token}\n${ticket.controlToken.orEmpty()}\n"
    }

    fun decode(text: String): VirtualDisplayPreviewHttpServer.Ticket? {
        if (text.length > MAX_BYTES) return null
        val lines = text.split('\n')
        if (lines.size != 5 || lines[0] != "1" || lines[4].isNotEmpty()) return null
        if (!lines[1].matches(Regex("[1-9][0-9]{3,4}"))) return null
        val port = lines[1].toIntOrNull() ?: return null
        val ticket = VirtualDisplayPreviewHttpServer.Ticket(port, lines[2], lines[3].ifEmpty { null })
        return ticket.takeIf(::valid)
    }
}
