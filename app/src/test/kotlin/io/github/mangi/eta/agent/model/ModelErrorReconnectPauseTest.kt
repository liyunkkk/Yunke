package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.junit.Assert.*
import org.junit.Test

class ModelErrorReconnectPauseTest {
    private class Clock : ReconnectTiming {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
            val task = Task(now + delayMs, action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                task.action()
            }
            now = end
        }
    }
    private fun state(clock: Clock, events: MutableList<AgentEvent>, window: Long? = 30_000L) =
        ModelErrorReconnect(1, window, clock, events::add,
            AgentModelFailure("HTTP_500", false, "offline"), emptyList())
    private fun List<AgentEvent>.changes() = filterIsInstance<AgentEvent.ErrorReconnectChanged>()

    @Test fun pauseFreezesTimerAndRemainingBudgetAndResumeUsesANewSegment() {
        val clock = Clock()
        val control = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val reconnect = state(clock, events)
        val binding = reconnect.bind(control)
        reconnect.start()
        clock.advance(3_000)
        val staleTick = clock.tasks.first { !it.cancelled }.action
        control.pause()
        assertEquals("stopped", events.changes().last().status)
        assertEquals(3_000L, events.changes().last().elapsedMs)
        val oldId = events.changes().last().reconnectId
        val count = events.size
        clock.advance(120_000)
        reconnect.check()
        assertEquals(27_000L, reconnect.remainingMs())
        assertEquals(count, events.size)
        assertFalse(control.isCancelled)
        control.resume()
        assertEquals("running", events.changes().last().status)
        assertNotEquals(oldId, events.changes().last().reconnectId)
        val resumedCount = events.size
        staleTick() // Already queued callback from the old segment must not restart its timer.
        assertEquals(resumedCount, events.size)
        clock.advance(26_000)
        reconnect.check()
        assertEquals(1_000L, reconnect.remainingMs())
        clock.advance(1_000)
        assertEquals("ERROR_RECONNECT_DEADLINE",
            assertThrows(AgentModelFailure::class.java) { reconnect.check() }.code)
        binding.close()
        reconnect.finish("failed")
    }

    @Test fun repeatedPauseDoesNotCancelToolOwnersAndBindingIsRemovedAtCompletion() {
        val clock = Clock()
        val control = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        var toolStops = 0
        control.register { toolStops++ }
        val reconnect = state(clock, events, null)
        val binding = reconnect.bind(control)
        reconnect.start()
        repeat(2) {
            clock.advance(1_000)
            control.pause()
            val count = events.size
            control.pause()
            clock.advance(60_000)
            assertEquals(count, events.size)
            assertEquals(0, toolStops)
            control.resume()
        }
        assertEquals(3, events.changes().map { it.reconnectId }.distinct().size)
        reconnect.finish("succeeded")
        binding.close()
        val count = events.size
        control.pause()
        control.resume()
        clock.advance(60_000)
        assertEquals(count, events.size)
        control.cancel()
        assertEquals(1, toolStops)
    }

    @Test fun bindingWhileAlreadyPausedCannotStartTimerOrAcceptSuccess() {
        val clock = Clock()
        val control = AgentRunController()
        control.pause()
        val events = mutableListOf<AgentEvent>()
        val reconnect = state(clock, events)
        val binding = reconnect.bind(control)
        reconnect.start()
        var transportStops = 0
        val scope = control.newTransportScope()
        reconnect.attach(scope)
        scope.run { control.register(interruptible = true) { transportStops++ } }
        assertEquals(1, transportStops)
        assertTrue(control.hasPausedInterrupt)
        val count = events.size
        clock.advance(60_000)
        assertEquals(count, events.size)
        reconnect.finish("succeeded")
        assertEquals("stopped", events.changes().last().status)
        control.resume()
        assertTrue(events.changes().none { it.status == "succeeded" || it.status == "running" })
        binding.close()
    }

    @Test fun cancellingWhilePausedIsTerminalAndCannotResumeOldReconnect() {
        val clock = Clock()
        val control = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val reconnect = state(clock, events)
        val binding = reconnect.bind(control)
        reconnect.start()
        control.pause()
        control.cancel()
        val count = events.size
        control.resume()
        clock.advance(60_000)
        assertEquals(count, events.size)
        assertEquals("stopped", events.changes().last().status)
        binding.close()
    }
    @Test fun pauseExpiresTheAttemptGateEvenWhenResumedImmediately() {
        val clock = Clock()
        val control = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val reconnect = state(clock, events)
        val binding = reconnect.bind(control)
        reconnect.start()
        val scope = control.newTransportScope()
        reconnect.attach(scope)
        var cancellations = 0
        scope.run { control.register(interruptible = true) { cancellations++ } }
        control.pause()
        control.resume()
        assertTrue(scope.isExpired)
        assertEquals(1, cancellations)
        assertFalse(control.isCancelled)
        reconnect.finish("stopped")
        binding.close()
    }

}
