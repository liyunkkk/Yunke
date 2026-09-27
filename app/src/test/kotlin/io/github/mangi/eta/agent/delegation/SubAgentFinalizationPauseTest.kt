package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SubAgentFinalizationPauseTest {
    private val model = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "child", systemPrompt = "",
    )
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())
    private fun snapshot(c: SubAgentCoordinator, id: String) =
        JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content)

    @Test fun providerReturnWhilePausedWaitsWithoutSpinningAndKeepsAnswerForContinuation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        SubAgentCoordinator(listOf(model)) { _, _, _ ->
            worker.set(Thread.currentThread())
            entered.countDown()
            release.await()
            "confirmed answer before finalization"
        }.use { c ->
            try {
                val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "work"))).content).getString("task_id")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                c.pauseGroup()
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var paused = snapshot(c, id)
                while ((!paused.optBoolean("pause_confirmed") || worker.get().state != Thread.State.WAITING) &&
                    System.nanoTime() < deadline) {
                    Thread.sleep(5)
                    paused = snapshot(c, id)
                }
                assertEquals("awaiting_decision", paused.getString("status"))
                assertTrue(paused.getBoolean("pause_confirmed"))
                assertEquals(Thread.State.WAITING, worker.get().state)
                assertEquals("confirmed answer before finalization", paused.getString("result"))
                c.resumeGroup()
                val finish = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var done = snapshot(c, id)
                while (done.optString("status") != "completed" && System.nanoTime() < finish) {
                    Thread.sleep(5)
                    done = snapshot(c, id)
                }
                assertEquals("completed", done.getString("status"))
                assertEquals(paused.getString("result"), done.getString("result"))
            } finally { release.countDown() }
        }
    }
}
