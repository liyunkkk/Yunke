package io.github.mangi.eta.agent.voice

/**
 * SeedDuplex ASR hypotheses replace the whole sentence. The second pass often
 * retracts only trailing punctuation and then puts it back, which looks like a blink.
 */
internal object DuplexAsrHypothesis {
    fun display(previous: String, incoming: String, completed: Boolean): String {
        if (completed) return incoming.ifEmpty { previous }
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty() || incoming == previous) return incoming
        val previousBody = stripTrailingPunctuation(previous)
        val incomingBody = stripTrailingPunctuation(incoming)
        if (incomingBody == previousBody && incoming.length < previous.length) return previous
        return incoming
    }

    internal fun stripTrailingPunctuation(text: String): String {
        var end = text.length
        while (end > 0 && isTrailingPunctuation(text[end - 1])) end--
        return text.substring(0, end)
    }

    private fun isTrailingPunctuation(char: Char): Boolean {
        if (char.isWhitespace()) return true
        if (char.isLetterOrDigit()) return false
        return when (Character.getType(char)) {
            Character.OTHER_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            -> true
            else -> char == '…' || char == '—' || char == '～'
        }
    }
}
