package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SubAgentReplacementIsolationTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "", providerId = "provider-one")
    private fun call(c: SubAgentCoordinator, name: String, args: JSONObject): JSONObject =
        JSONObject(c.execute(AgentModelClient.ToolCall("call", name, args.toString())).content)
    private fun start(c: SubAgentCoordinator, worker: Int, replace: String? = null): JSONObject {
        val args = JSONObject().put("task", "verify").put("worker", worker)
        if (replace != null) args.put("replace_task_id", replace)
        return call(c, "delegate_task", args)
    }
    private fun get(c: SubAgentCoordinator, id: String): JSONObject =
        call(c, "get_task_result", JSONObject().put("task_id", id).put("wait_ms", 1000))
    private fun awaitReleased(c: SubAgentCoordinator) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (c.hasActiveTasks() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("worker did not exit", c.hasActiveTasks())
    }

    @Test fun failedPredecessorMayHaveOnlyOneSuccessorEvenWhenDifferentWorkersAreRequested() {
        val called = java.util.concurrent.atomic.AtomicInteger()
        SubAgentCoordinator(listOf(model, model.copy(providerId = "provider-two"), model.copy(providerId = "provider-three")),
            executeChild = { _, _, _ -> if (called.incrementAndGet() == 1) error("failed") else "success" }).use { c ->
            val a = start(c, 1).getString("task_id")
            assertEquals("failed", get(c, a).getString("status"))
            awaitReleased(c)
            val invalid = call(c, "delegate_task", JSONObject().put("task", "verify").put("worker", 99).put("replace_task_id", a))
            assertFalse(invalid.getBoolean("ok"))
            assertEquals("failed", get(c, a).getString("status"))
            val b = start(c, 2, a)
            assertTrue(b.toString(), b.getBoolean("ok"))
            val again = start(c, 3, a)
            assertFalse(again.toString(), again.getBoolean("ok"))
            assertEquals("REPLACEMENT_ALREADY_DISPATCHED", again.getString("code"))
            assertEquals(b.getString("task_id"), again.getString("task_id"))
            assertEquals("completed", get(c, b.getString("task_id")).getString("status"))
            assertEquals(2, called.get())
        }
    }

    @Test fun healthyAndManualPauseNeverDestroyedByReplacementOrInvalidCandidate() {
        val running = CountDownLatch(1)
        val exit = CountDownLatch(1)
        SubAgentCoordinator(listOf(model, model.copy(providerId = "provider-two")),
            executeChild = { _, _, _ -> running.countDown(); exit.await(); "done" }).use { c ->
            val a = start(c, 1).getString("task_id")
            assertTrue(running.await(2, TimeUnit.SECONDS))
            val invalid = start(c, 2, a)
            assertEquals("REPLACEMENT_NOT_ALLOWED", invalid.getString("code"))
            assertEquals("running", get(c, a).getString("status"))
            val pause = call(c, "supervise_task", JSONObject().put("task_id", a).put("action", "pause"))
            assertEquals("awaiting_decision", pause.getString("status"))
            assertEquals("SUB_AGENT_MANUAL_PAUSE", pause.getString("error_code"))
            assertEquals("REPLACEMENT_NOT_ALLOWED", start(c, 2, a).getString("code"))
            assertEquals("awaiting_decision", get(c, a).getString("status"))
            exit.countDown()
            call(c, "cancel_task", JSONObject().put("task_id", a))
        }
    }

    @Test fun scopedPoolsDoNotQueueAcrossRunsWithSameProviderAndApiModel() {
        val both = CountDownLatch(2)
        val exit = CountDownLatch(1)
        fun coordinator(scope: String) = SubAgentCoordinator(listOf(model), modelParallelLimits = listOf(1), poolScope = scope,
            executeChild = { _, _, _ -> both.countDown(); exit.await(); "ok" })
        coordinator("session-A:run-1").use { a ->
            coordinator("session-A:run-2").use { b ->
                val first = start(a, 1).getString("task_id")
                val second = start(b, 1).getString("task_id")
                try { assertTrue("different scoped runs must not share one queue", both.await(2, TimeUnit.SECONDS)) }
                finally { exit.countDown() }
                assertEquals("completed", get(a, first).getString("status"))
                assertEquals("completed", get(b, second).getString("status"))
            }
        }
    }

    @Test fun telemetryCanQueryTaskWithoutCoordinatorTaskLockInversion() {
        val callbackFinished = CountDownLatch(1)
        val entered = AtomicBoolean(false)
        val callbackUnlocked = AtomicBoolean(false)
        val callbackReturned = CountDownLatch(1)
        lateinit var c: SubAgentCoordinator
        c = SubAgentCoordinator(listOf(model), onContext = { stats ->
            if (entered.compareAndSet(false, true)) {
                val reader = Thread { get(c, stats.taskId); callbackFinished.countDown() }
                reader.start()
                callbackUnlocked.set(callbackFinished.await(2, TimeUnit.SECONDS))
                callbackReturned.countDown()
            }
        }, executeChild = { _, _, _ -> "done" })
        c.use { coordinator ->
            val id = start(coordinator, 1).getString("task_id")
            assertEquals("completed", get(coordinator, id).getString("status"))
            assertTrue(callbackReturned.await(3, TimeUnit.SECONDS))
            assertTrue("task lock held while calling external telemetry", callbackUnlocked.get())
        }
    }

    @Test fun releasingTerminalResultsDoesNotCancelOrDiscardThem() {
        SubAgentCoordinator(listOf(model), executeChild = { _, _, _ -> "kept" }).use { c ->
            val id = start(c, 1).getString("task_id")
            assertEquals("completed", get(c, id).getString("status"))
            awaitReleased(c)
            c.releaseExecutionResources()
            assertEquals("completed", get(c, id).getString("status"))
            assertEquals("kept", get(c, id).getString("result"))
            assertEquals("RUN_CLOSED", start(c, 1).getString("code"))
        }
    }

    @Test fun releaseRejectsLiveAndPausedWorkersWithoutClosingThem() {
        val running = CountDownLatch(1)
        val exit = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), executeChild = { _, _, _ -> running.countDown(); exit.await(); "done" }).use { c ->
            val id = start(c, 1).getString("task_id")
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { c.releaseExecutionResources() }
            call(c, "supervise_task", JSONObject().put("task_id", id).put("action", "pause"))
            assertThrows(IllegalStateException::class.java) { c.releaseExecutionResources() }
            assertEquals("awaiting_decision", get(c, id).getString("status"))
            exit.countDown()
            call(c, "cancel_task", JSONObject().put("task_id", id))
        }
    }
}
