package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Static regression coverage; tests have not been executed in this worktree. */
class SubAgentSupervisionTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "private-key", model = "actual-model", systemPrompt = "")
    private fun call(c: SubAgentCoordinator, name: String, args: JSONObject): JSONObject =
        JSONObject(c.execute(AgentModelClient.ToolCall("id", name, args.toString())).content)

    @Test fun progressRequiresSuccessfulBusinessToolOrAppliedCompaction() {
        var now = 0L
        val journal = SubAgentEventJournal(now = { now })
        now = 10
        assertFalse(journal.accept(AgentEvent.ToolFinished(1, "t", "read_file", "failed", 0, 0, false)))
        assertEquals(0L, journal.lastProgressMs)
        now = 20
        assertFalse(journal.accept(AgentEvent.ContextCompacted(1, false, 10, 10)))
        assertEquals(0L, journal.lastProgressMs)
        now = 25
        assertFalse(journal.accept(AgentEvent.ToolFinished(1, "t", "report_task_progress", "reported", 0, 0, true)))
        assertEquals(0L, journal.lastProgressMs)
        now = 30
        assertTrue(journal.accept(AgentEvent.ToolFinished(1, "t", "read_file", "done", 0, 0, true)))
        assertEquals(30L, journal.lastProgressMs)
        now = 40
        assertTrue(journal.accept(AgentEvent.ContextCompacted(1, true, 10, 4)))
        assertEquals(40L, journal.lastProgressMs)
    }

    @Test fun boundaryGuidanceIsBoundedAndDoesNotInterruptTransport() {
        val controller = AgentRunController()
        val interrupted = CountDownLatch(1)
        val binding = controller.register(interruptible = true) { interrupted.countDown() }
        try {
            repeat(16) { assertTrue(controller.queueBoundaryGuidance("message-$it")) }
            assertFalse(controller.queueBoundaryGuidance("overflow"))
            assertFalse(controller.queueBoundaryGuidance("message-0"))
            assertFalse(interrupted.await(25, TimeUnit.MILLISECONDS))
        } finally { binding.close() }
    }

    @Test fun waitCursorWakesForCheckpointWithoutWaitingForChildCompletion() {
        val started = CountDownLatch(1)
        val report = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), executeChild = { _, _, controller ->
            started.countDown()
            report.await()
            assertTrue(controller.reportTaskProgress("Verified inventory; next inspect failures"))
            release.await()
            "done"
        }).use { c ->
            val id = call(c, "delegate_task", JSONObject().put("task", "inspect")).getString("task_id")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val cursor = call(c, "get_task_result", JSONObject().put("task_id", id))
                .getJSONObject("supervision").getLong("latest_seq")
            try {
                report.countDown()
                val next = call(c, "get_task_result", JSONObject().put("task_id", id).put("after_seq", cursor).put("wait_ms", 1000))
                assertEquals("running", next.getString("status"))
                assertEquals("Verified inventory; next inspect failures", next.getJSONObject("supervision").getString("checkpoint"))
            } finally { release.countDown() }
        }
    }

    @Test fun statusNotificationsAndActualModelDoNotRevealKey() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        SubAgentCoordinator(listOf(model), onTaskChanged = { notifications.incrementAndGet() }, executeChild = { _, _, controller ->
            started.countDown(); release.await(); controller.throwIfCancelled(); "done"
        }).use { c ->
            val id = call(c, "delegate_task", JSONObject().put("task", "inspect")).getString("task_id")
            assertTrue(c.ownsTask(id))
            assertTrue(c.taskIds().contains(id))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val initial = call(c, "get_task_result", JSONObject().put("task_id", id))
            assertEquals("actual-model", initial.getString("model"))
            assertFalse(initial.toString().contains("private-key"))
            assertEquals("", initial.getJSONObject("supervision").getString("checkpoint"))
            release.countDown()
            assertEquals("completed", call(c, "get_task_result", JSONObject().put("task_id", id).put("wait_ms", 1000)).getString("status"))
            assertTrue(notifications.get() >= 2)
        }
    }
}
