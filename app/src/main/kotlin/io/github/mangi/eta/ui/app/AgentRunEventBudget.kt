package io.github.mangi.eta.ui.app

import java.util.ArrayDeque

/**
 * FIFO event buffer with a cooperative time budget.
 *
 * Events are removed only after the consumer returns successfully. A budget
 * never drops or reorders events; it only decides when the caller should yield
 * back to the main dispatcher and continue on a later turn.
 */
internal class AgentRunEventBudget<T>(
    private val budgetNs: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val pending = ArrayDeque<T>()

    val isEmpty: Boolean get() = pending.isEmpty()
    val size: Int get() = pending.size

    fun offer(value: T) {
        pending.addLast(value)
    }

    fun drain(force: Boolean, consume: (T) -> Unit): Int {
        if (pending.isEmpty()) return 0
        val started = clock()
        var consumed = 0
        while (pending.isNotEmpty()) {
            val value = pending.peekFirst()
            consume(value)
            pending.removeFirst()
            consumed++
            if (!force && consumed > 0 && clock() - started >= budgetNs) break
        }
        return consumed
    }
}
