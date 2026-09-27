package io.github.mangi.eta.agent.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceAccessPolicyTest {
    @Test
    fun listPaginationIsValidatedAndReachesBackendUnchanged() {
        val allowed = AgentWorkspaceAccessPolicy.execute(
            argumentsJson = """{"project":"/workspace/Project","action":"list","offset":50,"limit":10}""",
            requestAllowsTerminal = true, runtimeAllowsTerminal = true, backendAvailable = true,
            ownsWorkspace = { _, _ -> error("empty list must not require existing ownership") },
            backendOperation = { request ->
                assertEquals(50, request.offset)
                assertEquals(10, request.limit)
                JSONObject().put("ok", true)
            },
        )
        assertTrue(allowed.getBoolean("ok"))
        for ((key, value) in listOf("offset" to -1, "limit" to 0, "limit" to 51, "offset" to "50", "limit" to 1.5)) {
            val denied = AgentWorkspaceAccessPolicy.execute(
                argumentsJson = JSONObject().put("project", "/workspace/Project").put("action", "list").put(key, value).toString(),
                requestAllowsTerminal = true, runtimeAllowsTerminal = true, backendAvailable = true,
                ownsWorkspace = { _, _ -> error("invalid page must not check ownership") },
                backendOperation = { error("invalid page must not execute backend") },
            )
            assertEquals("WORKSPACE_INVALID_ARGUMENTS", denied.getString("code"))
            assertFalse(denied.getBoolean("shell_executed"))
        }
    }

    @Test
    fun invalidProjectNeverReachesOwnershipOrBackend() {
        for (project in listOf("/workspace", "/workspace/.", "/workspace/..", "/workspace/a/b", "/tmp/project")) {
            val result = AgentWorkspaceAccessPolicy.execute(
                argumentsJson = JSONObject().put("project", project).put("action", "list").toString(),
                requestAllowsTerminal = true,
                runtimeAllowsTerminal = true,
                backendAvailable = true,
                ownsWorkspace = { _, _ -> error("invalid project must not reach ownership") },
                backendOperation = { error("invalid project must not reach backend") },
            )
            assertEquals("WORKSPACE_INVALID_ARGUMENTS", result.getString("code"))
            assertFalse(result.getBoolean("shell_executed"))
        }
    }

    @Test
    fun nonListActionsPreserveExactWorkspaceIdAndBackendRejection() {
        for (action in listOf("inspect", "merge", "discard")) {
            val expected = JSONObject().put("ok", false).put("code", "REVIEW_REQUIRED")
            val result = AgentWorkspaceAccessPolicy.execute(
                argumentsJson = JSONObject().put("project", "/workspace/Project")
                    .put("action", action).put("workspace_id", "exact-id").toString(),
                requestAllowsTerminal = true,
                runtimeAllowsTerminal = true,
                backendAvailable = true,
                ownsWorkspace = { project, id -> project == "/workspace/Project" && id == "exact-id" },
                backendOperation = { request ->
                    assertEquals("exact-id", request.workspaceId)
                    assertEquals(action, request.action)
                    expected
                },
            )
            assertTrue(result === expected)
            assertFalse(result.has("shell_executed"))
        }
    }

    @Test
    fun rejectsMalformedArgumentsWithShellFalse() {
        var ownershipChecks = 0
        val result = AgentWorkspaceAccessPolicy.execute(
            argumentsJson = "not-json",
            requestAllowsTerminal = true,
            runtimeAllowsTerminal = true,
            backendAvailable = true,
            ownsWorkspace = { _, _ -> ownershipChecks++; true },
            backendOperation = { error("backend must not execute") },
        )

        assertEquals("WORKSPACE_INVALID_ARGUMENTS", result.getString("code"))
        assertTrue(result.getString("message").contains("未执行 shell"))
        assertFalse(result.getBoolean("shell_executed"))
        assertEquals(0, ownershipChecks)
    }

    @Test
    fun distinguishesInvalidActionTerminalAndBackendBeforeOwnership() {
        var ownershipChecks = 0
        fun check(arguments: String, requestAllowed: Boolean, runtimeAllowed: Boolean, backend: Boolean, code: String) {
            val result = AgentWorkspaceAccessPolicy.execute(
                argumentsJson = arguments,
                requestAllowsTerminal = requestAllowed,
                runtimeAllowsTerminal = runtimeAllowed,
                backendAvailable = backend,
                ownsWorkspace = { _, _ -> ownershipChecks++; true },
                backendOperation = { error("backend must not execute") },
            )
            assertEquals(code, result.getString("code"))
            assertFalse(result.getBoolean("shell_executed"))
            assertTrue(result.getString("message").contains("未执行 shell"))
        }

        check("""{"project":"/workspace/Project","action":"unknown"}""", true, true, true, "WORKSPACE_INVALID_ACTION")
        check("""{"project":"/workspace/Project","action":"list"}""", false, true, true, "WORKSPACE_TERMINAL_UNAVAILABLE")
        check("""{"project":"/workspace/Project","action":"list"}""", true, false, true, "WORKSPACE_TERMINAL_UNAVAILABLE")
        check("""{"project":"/workspace/Project","action":"list"}""", true, true, false, "WORKSPACE_BACKEND_UNAVAILABLE")
        assertEquals(0, ownershipChecks)
    }

    @Test
    fun rejectsMissingWorkspaceIdForEveryNonListAction() {
        listOf("inspect", "merge", "discard").forEach { action ->
            val result = AgentWorkspaceAccessPolicy.execute(
                argumentsJson = JSONObject().put("project", "/workspace/Project").put("action", action).toString(),
                requestAllowsTerminal = true,
                runtimeAllowsTerminal = true,
                backendAvailable = true,
                ownsWorkspace = { _, _ -> error("ownership must not execute") },
                backendOperation = { error("backend must not execute") },
            )
            assertEquals("WORKSPACE_INVALID_ARGUMENTS", result.getString("code"))
            assertTrue(result.getString("message").contains("未执行 shell"))
            assertFalse(result.getBoolean("shell_executed"))
        }
    }

    @Test
    fun validListDoesNotRequireExistingOwnershipAndReturnsScopedBackendResult() {
        var ownershipArguments: Pair<String, String?>? = null
        var backendCalls = 0
        val expected = JSONObject().put("ok", true).put("items", "backend-value")
        val result = AgentWorkspaceAccessPolicy.execute(
            argumentsJson = """{"project":"/workspace/Project","action":"list"}""",
            requestAllowsTerminal = true,
            runtimeAllowsTerminal = true,
            backendAvailable = true,
            ownsWorkspace = { project, workspaceId ->
                ownershipArguments = project to workspaceId
                true
            },
            backendOperation = { request ->
                backendCalls++
                assertEquals("list", request.action)
                assertEquals(null, request.workspaceId)
                expected
            },
        )

        assertEquals(null, ownershipArguments)
        assertEquals(null, ownershipArguments?.second)
        assertEquals(1, backendCalls)
        assertEquals(expected.toString(), result.toString())
        assertFalse(result.has("shell_executed"))
    }

    @Test
    fun rejectsUnownedWorkspaceWithoutCallingBackend() {
        var backendCalls = 0
        val result = AgentWorkspaceAccessPolicy.execute(
            argumentsJson = """{"project":"/workspace/Project","action":"inspect","workspace_id":"owned-id"}""",
            requestAllowsTerminal = true,
            runtimeAllowsTerminal = true,
            backendAvailable = true,
            ownsWorkspace = { project, workspaceId ->
                assertEquals("/workspace/Project", project)
                assertEquals("owned-id", workspaceId)
                false
            },
            backendOperation = {
                backendCalls++
                error("backend must not execute")
            },
        )

        assertEquals("WORKSPACE_NOT_OWNED", result.getString("code"))
        assertFalse(result.getBoolean("shell_executed"))
        assertEquals(0, backendCalls)
    }
}
