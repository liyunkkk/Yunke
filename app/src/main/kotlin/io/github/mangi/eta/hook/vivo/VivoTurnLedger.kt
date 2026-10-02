package io.github.mangi.eta.hook.vivo

/** Lifecycle starts before the native wait UI/mapper, so a pre-dispatch stop is remembered. */
internal class VivoTurnLedger(private val limit: Int = 256) {
    enum class Claim { START, ACTIVE_DUPLICATE, FINISHED_DUPLICATE, CANCELLED, FULL }
    private enum class Phase { OBSERVED, ACTIVE, DONE, CANCELLED, LIMITED }
    class Turn internal constructor(val link: String, val dialog: String) {
        internal var phase = 0
        internal var renderCancel = false
    }
    private val turns = LinkedHashMap<Pair<String, String>, Turn>()
    private var overflow: Turn? = null
    @Synchronized fun begin(link: String, dialog: String): Turn {
        val key = link to dialog
        turns[key]?.let { return it }
        if (turns.size >= limit) return Turn(link, dialog).also {
            it.phase = Phase.LIMITED.ordinal; overflow = it
        }
        return Turn(link, dialog).also { turns[key] = it }
    }
    @Synchronized fun find(dialog: String): Turn? {
        val matches = turns.values.filter { it.dialog == dialog }
        return if (matches.size == 1) matches[0] else if (matches.isEmpty()) overflow?.takeIf { it.dialog == dialog } else null
    }
    @Synchronized fun claim(turn: Turn): Claim = when (Phase.entries[turn.phase]) {
        Phase.OBSERVED -> { turn.phase = Phase.ACTIVE.ordinal; Claim.START }
        Phase.ACTIVE -> Claim.ACTIVE_DUPLICATE
        Phase.DONE -> Claim.FINISHED_DUPLICATE
        Phase.CANCELLED -> Claim.CANCELLED
        Phase.LIMITED -> { turn.phase = Phase.DONE.ordinal; Claim.FULL }
    }
    @Synchronized fun active(turn: Turn) = turn.phase == Phase.ACTIVE.ordinal
    @Synchronized fun finish(turn: Turn): Boolean {
        if (!active(turn)) return false
        turn.phase = Phase.DONE.ordinal
        return true
    }
    @Synchronized fun cancel(link: String, dialog: String?, render: Boolean) {
        (turns.values.toList() + listOfNotNull(overflow)).filter {
            it.link == link && (dialog == null || it.dialog == dialog)
        }.forEach { turn ->
            if (turn.phase != Phase.DONE.ordinal) {
                turn.renderCancel = if (turn.phase == Phase.CANCELLED.ordinal) turn.renderCancel && render else render
                turn.phase = Phase.CANCELLED.ordinal
            }
        }
    }
    @Synchronized fun consumeCancelRender(turn: Turn): Boolean = turn.renderCancel.also { turn.renderCancel = false }
    @Synchronized fun cancelAll() {
        (turns.values.toList() + listOfNotNull(overflow)).forEach {
            if (it.phase != Phase.DONE.ordinal && it.phase != Phase.CANCELLED.ordinal) {
                it.phase = Phase.CANCELLED.ordinal; it.renderCancel = true
            }
        }
    }
}
