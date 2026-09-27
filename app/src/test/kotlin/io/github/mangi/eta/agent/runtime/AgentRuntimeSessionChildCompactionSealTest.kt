package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for "stopped but stuck saving the round": an admitted child compaction
 * that never returns used to block the terminal seal forever, so the run never published a
 * result and the UI stayed on the saving state. The seal must now be bounded, not abandoned.
 */
class AgentRuntimeSessionChildCompactionSealTest {
    @Test
    fun completeSealsOnItsOwnAfterTheBoundedWaitWhenAChildCompactionNeverFinishes() =
        assertTerminalSealsDespiteStuckChild(complete = true)

    @Test
    fun cancelSealsOnItsOwnAfterTheBoundedWaitWhenAChildCompactionNeverFinishes() =
        assertTerminalSealsDespiteStuckChild(complete = false)

    @Test
    fun completeSealsOnTheChildSignalInsteadOfWaitingOutTheDeadline() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val session = AgentRuntimeSession(RUN_ID, resultSink = { order += "result" })
        val childEntered = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        session.childCompactor = { _, _, _ ->
            order += "child-enter"
            childEntered.countDown()
            check(releaseChild.await(10, TimeUnit.SECONDS)) { "child was not released" }
            order += "child-return"
            true
        }
        val child = worker("released-child-compaction") {
            session.requestCompact(childTaskId = "child")
        }
        val terminal = worker("signalled-terminal") {
            session.complete(AgentRuntimeWire.RunResult(RUN_ID, true, "done")) { order += "persist" }
            order += "terminal-return"
        }

        child.start()
        try {
            assertTrue(childEntered.await(5, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            terminal.start()
            awaitAdmissionClosed(session)
            releaseChild.countDown()
            terminal.join(10_000)
            child.join(10_000)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            // The signal must release the waiter long before the seal budget would run out.
            assertTrue("seal only finished after the deadline: ${elapsedMs}ms",
                elapsedMs < AgentRuntimeSession.CHILD_COMPACTION_SEAL_TIMEOUT_MS / 2)
            assertEquals(listOf("child-enter", "child-return", "persist", "result", "terminal-return"),
                order.toList())
            assertTrue(session.isTerminal)
        } finally {
            releaseChild.countDown()
            terminal.join(10_000)
            child.join(10_000)
        }

        assertFalse(terminal.isAlive)
        assertFalse(child.isAlive)
        assertTrue(session.isTerminal)
    }

    @Test
    fun terminalClaimFromAChildCallbackStillSealsOnChildUnwind() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val session = AgentRuntimeSession(RUN_ID, resultSink = { order += "result" })
        var claimed: Boolean? = null
        var terminalInsideChild: Boolean? = null
        session.childCompactor = { _, _, _ ->
            order += "child-enter"
            claimed = session.complete(AgentRuntimeWire.RunResult(RUN_ID, true, "done")) { order += "persist" }
            terminalInsideChild = session.isTerminal
            order += "child-return"
            true
        }

        val startedAt = System.nanoTime()
        assertTrue(session.requestCompact(childTaskId = "child"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(true, claimed)
        assertEquals(false, terminalInsideChild)
        assertEquals(listOf("child-enter", "child-return", "persist", "result"), order.toList())
        assertTrue(session.isTerminal)
        // The last admitted child commits; the reentrant claim never enters the bounded wait.
        assertTrue("reentrant claim entered the bounded wait: ${elapsedMs}ms",
            elapsedMs < AgentRuntimeSession.CHILD_COMPACTION_SEAL_TIMEOUT_MS / 2)
    }

    @Test
    fun childCompactionSealTimeoutIsConfigured() {
        assertTrue(AgentRuntimeSession.CHILD_COMPACTION_SEAL_TIMEOUT_MS > 0)
    }

    private fun assertTerminalSealsDespiteStuckChild(complete: Boolean) {
        val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
        val order = Collections.synchronizedList(mutableListOf<String>())
        val session = AgentRuntimeSession(RUN_ID, resultSink = { results += it; order += "result" })
        val childEntered = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        session.childCompactor = { _, _, _ ->
            order += "child-enter"
            childEntered.countDown()
            check(releaseChild.await(30, TimeUnit.SECONDS)) { "child was not released" }
            order += "child-return"
            true
        }
        val child = worker("stuck-child-compaction") {
            session.requestCompact(childTaskId = "stuck-child")
        }

        child.start()
        try {
            assertTrue(childEntered.await(5, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            val accepted = if (complete) {
                session.complete(AgentRuntimeWire.RunResult(RUN_ID, true, "done")) { order += "persist" }
            } else {
                session.cancel("replaced")
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(accepted)
            // A stuck child must not be able to hold the seal open past the budget.
            assertTrue("seal gave up before the budget: ${elapsedMs}ms",
                elapsedMs >= AgentRuntimeSession.CHILD_COMPACTION_SEAL_TIMEOUT_MS / 2)
            assertTrue("seal was not bounded: ${elapsedMs}ms",
                elapsedMs <= AgentRuntimeSession.CHILD_COMPACTION_SEAL_TIMEOUT_MS * 2)
            assertFalse("the seal waited for the stuck child", order.contains("child-return"))
            assertTrue(session.isTerminal)
            assertNotNull(session.terminalResult)
            assertEquals(1, results.size)
            assertEquals(complete, session.terminalResult!!.ok)
            assertEquals(complete, results.single().ok)
            assertEquals(RUN_ID, results.single().runId)
            if (!complete) assertEquals("replaced", results.single().error)
        } finally {
            releaseChild.countDown()
            child.join(10_000)
        }

        assertFalse(child.isAlive)
    }

    /** Emit admission closes exactly when the terminal caller claims COMMITTING. */
    private fun awaitAdmissionClosed(session: AgentRuntimeSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.emit(AgentEvent.RoundStarted(round = 1, messageCount = 1))) {
            check(System.nanoTime() < deadline) { "terminal caller never claimed COMMITTING" }
            Thread.yield()
        }
    }

    private fun worker(name: String, action: () -> Unit) = Thread(action).apply {
        isDaemon = true
        this.name = name
    }

    private companion object {
        const val RUN_ID = "child-compaction-seal"
    }
}
