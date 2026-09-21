package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentWorkspaceTest {
    private fun call(action: String, extra: JSONObject = JSONObject()) = AgentModelClient.ToolCall(
        "test", "workspace_file", extra.put("action", action).toString())

    @Test fun childCannotOverrideProjectOrWorkspaceAndReviewerCannotWrite() {
        var executions = 0
        var command = ""
        val backend = SubAgentWorkspace("print('ok')") {
            executions++
            val args = JSONObject(it.argumentsJson)
            assertEquals("linux", args.getString("environment"))
            assertEquals("/workspace/ProjectA", args.getString("cwd"))
            command = args.getString("command")
            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("exit_code", 0)
                .put("stdout", "{\"ok\":true}").toString())
        }
        val readOnly = backend.childExecutor("/workspace/ProjectA", "owned", false, AgentRunController())
        assertTrue(readOnly.execute(call("write")).content.contains("SUB_AGENT_READ_ONLY"))
        assertTrue(readOnly.execute(call("merge")).content.contains("SUB_AGENT_READ_ONLY"))
        assertTrue(readOnly.execute(AgentModelClient.ToolCall("x", "terminal", "{}")).content.contains("WORKSPACE_ONLY"))
        assertEquals(0, executions)
        assertTrue(readOnly.execute(call("read", JSONObject().put("project", "/workspace/ProjectB")
            .put("workspace_id", "foreign").put("path", "main.kt"))).sensitive)
        assertEquals(1, executions)
        assertFalse(command.contains("ProjectB"))
        assertFalse(command.contains("foreign"))
        assertTrue(command.contains("owned"))
    }

    @Test fun schemasEnforceRoleSpecificActions() {
        val reader = AgentToolCallValidator(SubAgentWorkspace.childTools(false))
        val writer = AgentToolCallValidator(SubAgentWorkspace.childTools(true))
        val write = call("write", JSONObject().put("path", "test.kt").put("content", "text"))
        assertNotNull(reader.validate(write))
        assertNull(writer.validate(write))
        assertNull(reader.validate(call("read", JSONObject().put("path", "test.kt").put("offset", 2))))
        assertNotNull(writer.validate(call("merge")))
    }

    @Test fun cancellationPreventsAnyFurtherFileExecution() {
        val backend = SubAgentWorkspace("unused") { error("Must not execute") }
        val controller = AgentRunController().also { it.cancel() }
        assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
            backend.childExecutor("/workspace/Project", "id", true, controller).execute(call("list_files"))
        }
    }
    @Test fun transportFailureIsNotReportedAsMissingPythonOrGit() {
        val backend = SubAgentWorkspace("unused") {
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("exit_code", 2)
                .put("stderr", "cannot open staged script").toString())
        }
        assertEquals("WORKSPACE_EXECUTION_FAILED", backend.operation("/workspace/Test", "list").getString("code"))
    }

    @Test fun dependencyProbeAndTerminalErrorsRemainDistinct() {
        var command = ""
        val backend = SubAgentWorkspace("unused") {
            command = JSONObject(it.argumentsJson).getString("command")
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "LINUX_ENVIRONMENT_NOT_READY").toString())
        }
        assertEquals("LINUX_ENVIRONMENT_NOT_READY", backend.operation("/workspace/Test", "list").getString("code"))
        assertTrue(command.contains("command -v python3"))
        assertTrue(command.contains("command -v git"))
        assertTrue(command.contains("WORKSPACE_LINUX_PYTHON_GIT_REQUIRED"))
    }

}
