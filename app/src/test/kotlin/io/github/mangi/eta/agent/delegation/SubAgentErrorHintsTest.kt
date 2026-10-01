package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentErrorHintsTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("call", name, args.toString())

    @Test fun failuresWithoutMessageGetANextStep() {
        val annotated = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "TASK_GROUP_PAUSED"))
        assertTrue(annotated.getString("message").contains("不要重试 delegate_task"))
        val kept = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "TASK_GROUP_PAUSED").put("message", "原因"))
        assertEquals("原因", kept.getString("message"))
        val ok = SubAgentErrorHints.annotate(JSONObject().put("ok", true).put("code", "TASK_GROUP_PAUSED"))
        assertFalse(ok.has("message"))
        val unknown = SubAgentErrorHints.annotate(JSONObject().put("ok", false).put("code", "SOMETHING_NEW"))
        assertFalse(unknown.has("message"))
    }

    @Test fun unknownTaskIsNotReportedAsInvalidArguments() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" }.use { c ->
            for (name in listOf("get_task_result", "cancel_task", "continue_task")) {
                val result = JSONObject(c.execute(call(name, JSONObject().put("task_id", "missing"))).content)
                assertEquals("TASK_NOT_FOUND", result.getString("code"))
                assertTrue(result.getString("message").contains("get_task_result"))
            }
            val supervise = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", "missing").put("action", "pause"))).content)
            assertEquals("TASK_NOT_FOUND", supervise.getString("code"))
        }
    }

    @Test fun invalidArgumentsNameTheProblem() {
        SubAgentCoordinator(listOf(model, model), roles = listOf("image_generation", "research"),
            executeImageChild = { _, _, _, _ -> "image" }) { _, _, _ -> "done" }.use { c ->
            val media = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "draw").put("role", "image_generation")
                .put("project", "/workspace/p"))).content)
            assertEquals("INVALID_TASK_ARGUMENTS", media.getString("code"))
            assertTrue(media.getString("detail").contains("project"))
            assertTrue(media.getString("message").isNotBlank())
        }
    }

    @Test fun implementationWithoutWorkspaceBackendSaysSo() {
        SubAgentCoordinator(listOf(model), roles = listOf("implementation")) { _, _, _ -> "done" }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "edit").put("role", "implementation")
                .put("project", "/workspace/Eta"))).content)
            assertEquals("WORKSPACE_UNAVAILABLE", result.getString("code"))
            assertTrue(result.getString("message").contains("implementation"))
        }
    }

    @Test fun guideWithoutGuidanceExplainsTheMissingField() {
        val release = java.util.concurrent.CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, _ -> release.await(); "done" }.use { c ->
            try {
                val id = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
                val deadline = System.currentTimeMillis() + 2_000
                while (JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content).getString("status") != "running") {
                    assertTrue(System.currentTimeMillis() < deadline)
                    Thread.sleep(10)
                }
                val result = JSONObject(c.execute(call("supervise_task", JSONObject().put("task_id", id).put("action", "guide"))).content)
                assertEquals("INVALID_TASK_ARGUMENTS", result.getString("code"))
                assertTrue(result.getString("detail").contains("guidance"))
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun toolDescriptionsStateTheRulesModelsKeepBreaking() {
        val tools = JSONArray().also { SubAgentTools.appendTo(it, listOf("a", "b"), workspaceEnabled = true) }
        fun description(name: String): String {
            for (i in 0 until tools.length()) {
                val fn = tools.getJSONObject(i).getJSONObject("function")
                if (fn.getString("name") == name) return fn.getString("description")
            }
            error("missing $name")
        }
        val delegate = description("delegate_task")
        assertTrue(delegate.contains("TASK_GROUP_PAUSED"))
        assertTrue(delegate.contains("no uncommitted changes"))
        assertTrue(delegate.contains("never pass workspace_id"))
        assertTrue(delegate.contains("execution_stopped=true"))
        assertTrue(delegate.contains("different agent_id"))
        assertTrue(delegate.contains("missing shell is not a reason for the parent to read that source itself"))
        assertTrue(description("supervise_task").contains("exactly running"))
        assertTrue(description("manage_agent_workspace").contains("WORKSPACE_IN_USE"))
    }
}
