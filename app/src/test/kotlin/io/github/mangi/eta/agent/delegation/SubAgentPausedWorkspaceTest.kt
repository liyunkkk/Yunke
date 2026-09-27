package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubAgentPausedWorkspaceTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())
    private fun get(c: SubAgentCoordinator, id: String) = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content)

    @Test fun queuedPreparedWorkspaceKeepsLeaseAndIsNotFailedByGroupPause() {
        val operations = AtomicInteger()
        val workspace = SubAgentWorkspace("fixture", AgentModelClient.ToolExecutor {
            val id = operations.incrementAndGet().toString(16).padStart(32, '0')
            AgentModelClient.ToolResult(JSONObject().put("exit_code", 0).put("stdout", JSONObject().put("ok", true)
                .put("id", id).put("path", "/workspace/project/.agent/worktrees/$id").put("state", "editing").toString()).toString())
        })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        SubAgentCoordinator(listOf(model), roles = listOf("implementation"), workspace = workspace,
            executeWorkspaceChild = { _, _, controller, _, _, _ ->
                if (calls.incrementAndGet() == 1) { entered.countDown(); release.await() }
                controller.throwIfCancelled()
                "edited"
            }, executeChild = { _, _, _ -> error("workspace path expected") }).use { c ->
            fun start() = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "edit")
                .put("role", "implementation").put("project", "/workspace/project"))).content).getString("task_id")
            try {
                val first = start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val queued = start()
                val original = get(c, queued)
                c.pauseGroup()
                val paused = get(c, queued)
                assertEquals("awaiting_decision", paused.getString("status"))
                assertEquals(original.getString("workspace_id"), paused.getString("workspace_id"))
                assertTrue(paused.getBoolean("workspace_ownership_verified"))
                assertFalse(paused.getBoolean("can_replace"))
                assertEquals(2, operations.get()) // Only prepares; pause must not call fail/seal/discard.
                @Suppress("UNCHECKED_CAST")
                val tasks = c.javaClass.getDeclaredField("tasks").apply { isAccessible = true }.get(c) as Map<String, Any>
                for (id in listOf(first, queued)) {
                    val task = tasks.getValue(id)
                    val renewal = task.javaClass.getDeclaredField("leaseRenewal").apply { isAccessible = true }.get(task) as ScheduledFuture<*>
                    assertFalse(renewal.isCancelled)
                    assertFalse(renewal.isDone)
                }
                c.resumeGroup(); release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (c.hasActiveTasks() && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals("completed", get(c, queued).getString("status"))
                assertEquals(original.getString("workspace_id"), get(c, queued).getString("workspace_id"))
                assertEquals(2, calls.get())
            } finally { release.countDown() }
        }
    }
}
