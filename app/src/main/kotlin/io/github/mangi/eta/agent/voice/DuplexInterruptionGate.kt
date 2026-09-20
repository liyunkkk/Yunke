package io.github.mangi.eta.agent.voice

/** ASR started is only a candidate turn, not proof that the user spoke. */
internal class DuplexInterruptionGate {
    private var confirmed = false
    private var candidate = ""

    fun started() {
        confirmed = false
        candidate = ""
    }

    /** Hypotheses are replacements, not deltas. Keep short commands such as “停”. */
    fun transcript(text: String, completed: Boolean, onConfirmed: () -> Unit): String? {
        val current = text.takeIf(::hasSpeech) ?: candidate.takeIf { completed }
        if (current == null || !hasSpeech(current)) return null
        candidate = current
        if (!confirmed) {
            confirmed = true
            onConfirmed()
        }
        return current
    }

    private fun hasSpeech(text: String): Boolean = text.any(Char::isLetterOrDigit)
}
