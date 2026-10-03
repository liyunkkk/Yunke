package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Role rejection and failed-worktree recovery must stay actionable: the refusal names the roles the
 * selected worker can serve, and a failed isolated worktree never claims to be replaceable.
 */
class SubAgentDispatchRecoveryTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        providerId = "provider-a", model = "child", systemPrompt = "")

    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())
    private fun result(c: SubAgentCoordinator, name: String, args: JSONObject) =
        JSONObject(c.execute(call(name, args)).content)

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private fun awaitStatus(c: SubAgentCoordinator, id: String, status: String): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val json = result(c, "get_task_result", JSONObject().put("task_id", id).put("wait_ms", 1000))
            if (json.optString("status") == status && json.optBoolean("execution_exited")) return json
            Thread.sleep(5)
        }
        fail("task $id never reached $status")
        error("unreachable")
    }

    @Test
    fun roleMismatchNamesAllowedRolesWithoutUsingAWorkerOrCreatingATask() {
        val runs = AtomicInteger()
        SubAgentCoordinator(listOf(model, model.copy(model = "media")),
            roles = listOf("implementation", "image_generation"),
            workerIds = listOf("exec-agent", "media-agent"),
            executeImageChild = { _, _, _, _ -> runs.incrementAndGet(); "image" },
            executeChild = { _, _, _ -> runs.incrementAndGet(); "done" }).use { c ->
            val denied = result(c, "delegate_task", JSONObject().put("task", "draw")
                .put("agent_id", "exec-agent").put("role", "image_generation"))
            assertEquals("WORKER_ROLE_MISMATCH", denied.getString("code"))
            assertEquals("image_generation", denied.getString("requested_role"))
            assertEquals("exec-agent", denied.getString("agent_id"))
            assertEquals("implementation", denied.getString("worker_role"))
            assertEquals(listOf("research", "implementation"), strings(denied.getJSONArray("allowed_roles")))
            assertEquals(listOf("delegate_task", "get_task_result"), strings(denied.getJSONArray("allowed_actions")))
            assertTrue(denied.getString("next_step").contains("不会自动换"))

            val media = result(c, "delegate_task", JSONObject().put("task", "read")
                .put("agent_id", "media-agent").put("role", "research"))
            assertEquals("WORKER_ROLE_MISMATCH", media.getString("code"))
            assertEquals(listOf("image_generation"), strings(media.getJSONArray("allowed_roles")))

            // Fail-fast means neither model pool nor worktree was used: no child ran, no task exists.
            assertEquals(0, runs.get())
            assertEquals(0, result(c, "get_task_result", JSONObject()).getInt("total"))
        }
    }

    @Test
    fun missingRoleListsConfiguredRolesWithoutSilentlySwitchingWorker() {
        SubAgentCoordinator(listOf(model), roles = listOf("review")) { _, _, _ -> error("must not run") }.use { c ->
            val denied = result(c, "delegate_task", JSONObject().put("task", "implement")
                .put("role", "implementation").put("project", "/workspace/Test"))
            assertEquals("ROLE_NOT_CONFIGURED", denied.getString("code"))
            assertEquals("implementation", denied.getString("requested_role"))
            assertEquals(listOf("research", "review", "summary"), strings(denied.getJSONArray("allowed_roles")))
            assertEquals(listOf("delegate_task", "get_task_result"), strings(denied.getJSONArray("allowed_actions")))
            assertTrue(denied.getString("next_step").contains("implementation"))
            assertEquals(0, result(c, "get_task_result", JSONObject()).getInt("total"))
        }
    }

    @Test
    fun failedImplementationWorktreePointsAtInspectDiscardRelaunchAndNeverReplacement() {
        val id = "0123456789abcdef0123456789abcdef"
        val workspace = SubAgentWorkspace("fixture", AgentModelClient.ToolExecutor {
            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("exit_code", 0).put("stdout",
                JSONObject().put("ok", true).put("id", id)
                    .put("path", "/workspace/project/.agent/worktrees/$id").put("state", "editing").toString()).toString())
        })
        SubAgentCoordinator(listOf(model, model), roles = listOf("implementation", "implementation"),
            workspace = workspace,
            executeWorkspaceChild = { _, _, _, _, _, _ -> throw IllegalStateException("child failed") },
            executeChild = { _, _, _ -> error("workspace path expected") }).use { c ->
            val started = result(c, "delegate_task", JSONObject().put("task", "edit")
                .put("role", "implementation").put("project", "/workspace/project"))
            val taskId = started.getString("task_id")
            val failed = awaitStatus(c, taskId, "failed")
            assertFalse(failed.getBoolean("can_replace"))
            assertEquals("workspace_requires_manual_review", failed.getString("replace_reason"))
            assertEquals(id, failed.getString("workspace_id"))
            assertEquals(listOf("get_task_result", "manage_agent_workspace:inspect",
                "manage_agent_workspace:discard", "delegate_task"), strings(failed.getJSONArray("allowed_actions")))
            assertTrue(failed.getString("next_step").contains("不允许用 replace_task_id 替换 implementation"))

            // Another worker, same role: replacement is still refused because the worktree is isolated.
            val refused = result(c, "delegate_task", JSONObject().put("task", "retry").put("worker", 2)
                .put("project", "/workspace/project").put("replace_task_id", taskId))
            assertEquals("WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW", refused.getString("code"))
            assertFalse(refused.getBoolean("can_replace"))
            assertEquals(id, refused.getString("workspace_id"))
            assertTrue(strings(refused.getJSONArray("allowed_actions")).contains("manage_agent_workspace:discard"))
        }
    }
    @Test
    fun typedToolRepairExhaustionIsRoutedToSafeFailureWithoutProviderFallback() {
        val calls = AtomicInteger()
        SubAgentCoordinator(listOf(model, model.copy(providerId = "provider-b")), executeChild = { _, _, _ ->
            calls.incrementAndGet()
            throw io.github.mangi.eta.agent.model.AgentModelFailure(
                io.github.mangi.eta.agent.model.AgentInvalidToolArgumentsGuard.STOP_CODE,
                false, "secret provider body URL headers and arguments")
        }).use { c ->
            val started = result(c, "delegate_task", JSONObject().put("task", "check"))
            val failed = awaitStatus(c, started.getString("task_id"), "failed")
            assertEquals(io.github.mangi.eta.agent.model.AgentInvalidToolArgumentsGuard.STOP_CODE,
                failed.getString("error_code"))
            assertTrue(failed.getString("result").contains("任务未完成"))
            assertTrue(failed.getString("next_step").contains("不要据此替换"))
            assertFalse(failed.toString().contains("secret provider"))
            assertFalse(failed.getBoolean("can_replace"))
            assertEquals("local_tool_arguments_exhausted", failed.getString("replace_reason"))
            val replacement = result(c, "delegate_task", JSONObject().put("task", "replay")
                .put("worker", 2).put("replace_task_id", failed.getString("task_id")))
            assertEquals("REPLACEMENT_NOT_ALLOWED", replacement.getString("code"))
            assertFalse(replacement.getBoolean("can_replace"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun obfuscatedExceptionTextRemainsAnUnknownChildFailure() {
        SubAgentCoordinator(listOf(model), executeChild = { _, _, _ ->
            throw IllegalStateException("to secret provider body")
        }).use { c ->
            val started = result(c, "delegate_task", JSONObject().put("task", "check"))
            val failed = awaitStatus(c, started.getString("task_id"), "failed")
            assertEquals("SUB_AGENT_FAILED", failed.getString("error_code"))
            assertFalse(failed.getString("result").contains("secret provider"))
        }
    }

    @Test
    fun terminalTaskStillCleaningUpOnlyOffersResultReads() {
        val started = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), executeChild = { _, _, _ ->
            started.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { cleaning.countDown() }
            release.await()
            "late completion"
        }).use { c ->
            try {
                val task = result(c, "delegate_task", JSONObject().put("task", "check")).getString("task_id")
                assertTrue(started.await(2, TimeUnit.SECONDS))
                result(c, "cancel_task", JSONObject().put("task_id", task))
                assertTrue(cleaning.await(2, TimeUnit.SECONDS))
                val json = result(c, "get_task_result", JSONObject().put("task_id", task))
                assertEquals("cancelled", json.getString("status"))
                assertFalse(json.getBoolean("execution_exited"))
                assertFalse(json.getBoolean("can_replace"))
                assertEquals(listOf("get_task_result"), strings(json.getJSONArray("allowed_actions")))
                assertTrue(json.getString("next_step").contains("仍在收尾"))
                assertFalse(json.getString("next_step").contains("执行已停止"))
                release.countDown()
                assertTrue(awaitStatus(c, task, "cancelled").getBoolean("execution_exited"))
            } finally { release.countDown() }
        }
    }

    @Test
    fun stoppingPausedGroupDoesNotAdvertiseContinuation() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), executeChild = { _, _, _ ->
            started.countDown(); release.await(); "done"
        }).use { c ->
            try {
                val task = result(c, "delegate_task", JSONObject().put("task", "check")).getString("task_id")
                assertTrue(started.await(2, TimeUnit.SECONDS))
                result(c, "supervise_task", JSONObject().put("task_id", task).put("action", "pause"))
                c.preventNewTasks()
                val paused = result(c, "get_task_result", JSONObject().put("task_id", task))
                assertEquals("awaiting_decision", paused.getString("status"))
                assertFalse(paused.getBoolean("can_continue"))
                assertEquals(listOf("get_task_result"), strings(paused.getJSONArray("allowed_actions")))
                assertEquals("RUN_CLOSED", result(c, "continue_task", JSONObject().put("task_id", task)).getString("code"))
            } finally { release.countDown() }
        }
    }


    @Test
    fun stoppingOrClosedFailedTaskNeverAdvertisesReplacement() {
        SubAgentCoordinator(listOf(model), executeChild = { _, _, _ ->
            throw IllegalStateException("fixture failure")
        }).use { c ->
            val id = result(c, "delegate_task", JSONObject().put("task", "check")).getString("task_id")
            assertTrue(awaitStatus(c, id, "failed").getBoolean("can_replace"))
            c.preventNewTasks()
            val stopping = result(c, "get_task_result", JSONObject().put("task_id", id))
            assertFalse(stopping.getBoolean("can_replace"))
            assertEquals(listOf("get_task_result"), strings(stopping.getJSONArray("allowed_actions")))
            c.close()
            assertFalse(result(c, "get_task_result", JSONObject().put("task_id", id)).getBoolean("can_replace"))
        }
    }
}
