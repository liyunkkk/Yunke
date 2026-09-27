package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShellFailureGuardTest {
    private val guard = AgentShellFailureGuard
    private val call = AgentModelClient.ToolCall("call-1", "terminal", """
        {"action":"open_and_exec","environment":"linux","command":"git status"}
    """.trimIndent())

    @Test fun exit127WithCommandNotFoundIsAnnotatedAndStopsOnlyAtBoundedCount() {
        var state = AgentShellFailureGuard.State()
        var decision: AgentShellFailureGuard.Decision? = null
        repeat(3) {
            decision = guard.observe(state, call, result(stderr = "git: command not found", exitCode = 127))
            state = requireNotNull(decision).state
        }

        val final = requireNotNull(decision)
        assertTrue(final.stopMessage!!.contains(AgentShellFailureGuard.STOP_CODE))
        val annotated = JSONObject(final.result.content)
        assertEquals(AgentShellFailureGuard.FAILURE_CODE, annotated.getString("code"))
        assertTrue(annotated.getString("message").contains("debian"))
        assertTrue(annotated.getString("message").contains("git"))
        assertTrue(annotated.getString("message").contains("不会自动"))
        val diagnostic = annotated.getJSONObject("shell_failure_diagnostic")
        assertEquals(false, diagnostic.getBoolean("original_ok"))
        assertEquals(JSONObject.NULL, diagnostic.get("original_code"))
        assertFalse(diagnostic.has("original_result"))
        // The tool envelope itself still retains stderr and the exit code.
        assertEquals("git: command not found", annotated.getString("stderr"))
        assertEquals(127, annotated.getInt("exit_code"))
    }

    @Test fun exitZeroWithCommandNotFoundStillCountsAndDoesNotPretendSuccess() {
        val decision = guard.observe(AgentShellFailureGuard.State(), call,
            result(stderr = "git: not found", exitCode = 0, ok = true))
        assertEquals(AgentShellFailureGuard.FAILURE_CODE, JSONObject(decision.result.content).getString("code"))
        assertEquals(1, decision.state.failures.values.single())
        assertFalse(JSONObject(decision.result.content).getBoolean("ok"))
    }

    @Test fun rewritingCommandDoesNotResetFailureForSameExecutable() {
        var state = AgentShellFailureGuard.State()
        repeat(2) {
            state = guard.observe(state, call, result(stderr = "git: not found", exitCode = 127)).state
        }
        val rewritten = call.copy(argumentsJson = """{"action":"open_and_exec","environment":"linux","command":"git --version"}""")
        val decision = guard.observe(state, rewritten, result(stderr = "git: command not found", exitCode = 127))
        assertEquals(3, decision.state.failures.values.single())
        assertTrue(decision.stopMessage!!.contains("git"))
    }

    @Test fun actualAndroidRunCommandPipelineFailureIsCountedOncePerCall() {
        var state = AgentShellFailureGuard.State()
        repeat(3) { attempt ->
            val androidCall = call.copy(
                name = "run_command",
                argumentsJson = JSONObject().put("command", "git log --oneline | head -${attempt + 1}").toString(),
            )
            val decision = guard.observe(state, androidCall, result(
                stderr = "ash: git: not found\nash: git: not found",
                exitCode = 0,
                ok = true,
                environment = "android",
            ))
            state = decision.state
            assertEquals(attempt + 1, state.failures.values.single())
            assertEquals("run_command", state.failures.keys.single().tool)
            assertEquals("android", state.failures.keys.single().environment)
            assertEquals(0, JSONObject(decision.result.content).getInt("exit_code"))
            assertFalse(JSONObject(decision.result.content).getBoolean("ok"))
            if (attempt == 2) assertTrue(decision.stopMessage!!.contains("android"))
            else assertNull(decision.stopMessage)
        }
    }

    @Test fun normalStderrAndExit127WithoutShellEvidenceAreUntouched() {
        val normal = guard.observe(AgentShellFailureGuard.State(), call,
            result(stderr = "warning: using cached metadata", exitCode = 0))
        assertEquals(callResult("warning: using cached metadata", 0), normal.result.content)
        assertTrue(normal.state.failures.isEmpty())

        val unrelated127 = guard.observe(AgentShellFailureGuard.State(), call,
            result(stderr = "permission denied", exitCode = 127))
        assertEquals(callResult("permission denied", 127), unrelated127.result.content)
        assertTrue(unrelated127.state.failures.isEmpty())
    }

    @Test fun successfulLinuxInvocationClearsOnlyMatchingFailure() {
        var state = guard.observe(AgentShellFailureGuard.State(), call,
            result(stderr = "git: not found", exitCode = 127)).state
        val success = guard.observe(state, call, result(stderr = "", exitCode = 0, ok = true))
        assertTrue(success.state.failures.isEmpty())
        assertNull(success.stopMessage)
    }

    @Test fun environmentIsPartOfKeyAndOtherToolsOrPollingAreUnaffected() {
        var state = AgentShellFailureGuard.State()
        repeat(2) {
            state = guard.observe(state, call, result(stderr = "git: not found", exitCode = 127)).state
        }
        val android = call.copy(argumentsJson = """{"action":"open_and_exec","environment":"android","command":"git status"}""")
        val androidDecision = guard.observe(state, android,
            result(stderr = "git: not found", exitCode = 127, environment = "android"))
        assertEquals(2, androidDecision.state.failures.size)
        assertEquals(1, androidDecision.state.failures.entries.single { it.key.environment == "android" }.value)
        assertNull(androidDecision.stopMessage)

        val otherTool = call.copy(name = "get_task_result")
        assertTrue(guard.observe(AgentShellFailureGuard.State(), otherTool,
            result(stderr = "git: not found", exitCode = 127)).state.failures.isEmpty())

        val polling = call.copy(argumentsJson = """{"action":"read_async_result","environment":"linux","command":"git status"}""")
        assertTrue(guard.observe(AgentShellFailureGuard.State(), polling,
            result(stderr = "git: not found", exitCode = 127)).state.failures.isEmpty())
    }

    private fun result(
        stderr: String,
        exitCode: Int,
        ok: Boolean = false,
        environment: String = "debian",
    ): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(callResult(stderr, exitCode, ok, environment))

    private fun callResult(
        stderr: String,
        exitCode: Int,
        ok: Boolean = false,
        environment: String = "debian",
    ): String = JSONObject()
        .put("ok", ok)
        .put("environment", environment)
        .put("stderr", stderr)
        .put("stdout", "")
        .put("exit_code", exitCode)
        .toString()
}
