package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentWorkspaceOwnershipStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubAgentWorkspaceOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var privateFiles: File
    private val owner = "1".repeat(32)
    private val otherOwner = "2".repeat(32)
    private val id = "a".repeat(32)
    private val nextId = "b".repeat(32)
    private val foreignId = "c".repeat(32)
    private val project = "/workspace/Project"
    private val script = "ownership-test-script"
    private val ledger get() = File(privateFiles, "agent-workspace-ownership.json")

    @Before
    fun createPrivateDirectory() {
        privateFiles = temporary.newFolder("private-files")
    }

    private fun store() = AgentWorkspaceOwnershipStore(privateFiles)

    // Every backend gets a new store instance. No Context, Python, Git or terminal is used.
    private fun backend(
        ownerId: String = owner,
        environment: () -> String = { "debian" },
        legacyIds: (String) -> Set<String> = { emptySet() },
        execute: (JSONObject) -> AgentModelClient.ToolResult,
    ) = SubAgentWorkspace(
        script,
        SubAgentWorkspace.Ownership(ownerId, store(), environment, legacyIds),
        AgentModelClient.ToolExecutor { call ->
            assertEquals("terminal", call.name)
            val terminal = JSONObject(call.argumentsJson)
            assertEquals("open_and_exec", terminal.getString("action"))
            assertEquals("linux", terminal.getString("environment"))
            assertEquals(project, terminal.getString("cwd"))
            val quoted = terminal.getString("command")
                .substringAfter("python3 -I -c '$script' ", missingDelimiterValue = "")
            assertTrue("Expected a quoted JSON argument", quoted.startsWith("'") && quoted.endsWith("'"))
            val request = JSONObject(quoted.substring(1, quoted.length - 1).replace("'\"'\"'", "'"))
            assertEquals(project, request.getString("project"))
            execute(request)
        },
    )

    private fun terminal(result: JSONObject, environment: String = "debian") =
        AgentModelClient.ToolResult(JSONObject().put("ok", true).put("exit_code", 0)
            .put("environment", environment).put("stdout", result.toString()).toString())

    private fun prepared(workspaceId: String = id) = JSONObject().put("ok", true)
        .put("id", workspaceId).put("path", "$project/.agent/worktrees/$workspaceId")

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private fun workspaceIds(result: JSONObject): List<String> {
        val rows = result.getJSONArray("workspaces")
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
    }

    private fun assertFailure(result: JSONObject, code: String, executed: Boolean = false) {
        assertFalse(result.getBoolean("ok"))
        assertEquals(code, result.getString("code"))
        assertEquals(executed, result.getBoolean("shell_executed"))
    }

    @Test
    fun preparePersistsOwnershipAndFreshBackendsWithEmptyLegacyCanInspectMergeAndDiscard() {
        var executions = 0
        val initial = backend { request ->
            executions++
            assertEquals("prepare", request.getString("action"))
            terminal(prepared())
        }.requireOperation(project, "prepare")

        assertEquals(id, initial.getString("id"))
        assertEquals("$project/.agent/worktrees/$id", initial.getString("path"))
        assertTrue(ledger.isFile)
        assertEquals(setOf(id), store().ids(owner, "debian", project))
        val entries = JSONObject(ledger.readText()).getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(owner, entries.getJSONObject(0).getString("owner"))
        assertEquals(id, entries.getJSONObject(0).getString("id"))

        for ((action, state) in listOf("inspect" to "ready", "merge" to "merged", "discard" to "discarded")) {
            val reopened = backend(legacyIds = { emptySet() }) { request ->
                executions++
                assertEquals(action, request.getString("action"))
                assertEquals(id, request.getString("workspace_id"))
                terminal(prepared().put("state", state))
            }
            assertTrue(reopened.ownsWorkspace(project, id))
            assertTrue(reopened.ownsWorkspace(project, null))
            assertEquals(state, reopened.requireOperation(project, action, id).getString("state"))
        }
        assertEquals(4, executions)
        assertEquals(setOf(id), store().ids(owner, "debian", project))
    }

    @Test
    fun newSessionCannotInspectMergeOrDiscardAnExistingOwnersWorkspace() {
        backend { terminal(prepared()) }.requireOperation(project, "prepare")
        val before = ledger.readBytes()
        var executions = 0
        val stranger = backend(ownerId = otherOwner) {
            executions++
            terminal(prepared())
        }

        assertFalse(stranger.ownsWorkspace(project, id))
        assertFalse(stranger.ownsWorkspace(project, null))
        for (action in listOf("inspect", "merge", "discard", "begin_review", "read")) {
            assertFailure(stranger.operation(project, action, id), "WORKSPACE_NOT_OWNED")
        }
        assertEquals(0, executions)
        assertArrayEquals(before, ledger.readBytes())
        assertEquals(setOf(id), store().ids(owner, "debian", project))
        assertTrue(store().ids(otherOwner, "debian", project).isEmpty())
    }

    @Test
    fun diskResultJsonCannotCreateOwnershipOrTransferItToANewSession() {
        store().remember(owner, "debian", project, id)
        val before = ledger.readBytes()
        val guest = temporary.newFolder("guest")
        val results = File(guest, "workspace/Project/.agent/results").also { assertTrue(it.mkdirs()) }
        val diskRecords = listOf(id, foreignId).map { workspaceId ->
            File(results, "$workspaceId.json").also {
                it.writeText(prepared(workspaceId).put("owner", otherOwner)
                    .put("state", "ready").put("reviewed", true).toString())
            }
        }
        val diskBefore = diskRecords.map { it.readBytes() }
        var executions = 0
        val stranger = backend(ownerId = otherOwner) { request ->
            executions++
            // This mock would expose forged guest metadata if the private authorization gate ran it.
            val response = if (request.getString("action") == "list") {
                JSONObject().put("ok", true).put("workspaces", JSONArray().also { rows ->
                    diskRecords.forEach { rows.put(JSONObject(it.readText())) }
                })
            } else {
                JSONObject(File(results, "${request.getString("workspace_id")}.json").readText())
            }
            terminal(response)
        }

        val listed = stranger.operation(project, "list")
        assertTrue(listed.getBoolean("ok"))
        assertEquals(emptyList<String>(), workspaceIds(listed))
        assertFalse(listed.getBoolean("shell_executed"))
        for (workspaceId in listOf(id, foreignId)) {
            for (action in listOf("inspect", "merge", "discard")) {
                assertFailure(stranger.operation(project, action, workspaceId), "WORKSPACE_NOT_OWNED")
            }
            assertFalse(stranger.ownsWorkspace(project, workspaceId))
        }
        assertEquals(0, executions)
        assertArrayEquals(before, ledger.readBytes())
        diskRecords.forEachIndexed { index, file -> assertArrayEquals(diskBefore[index], file.readBytes()) }
        assertEquals(setOf(id), store().ids(owner, "debian", project))
        assertTrue(store().ids(otherOwner, "debian", project).isEmpty())
    }

    @Test
    fun emptyListReturnsLocallyEvenWithCallerSuppliedWorkspaceIds() {
        var executions = 0
        val backend = backend {
            executions++
            error("An empty private allowlist must not execute a terminal call")
        }
        val arguments = JSONObject().put("workspace_ids", JSONArray().put(id))
            .put("project", "/workspace/Other").put("action", "prepare")
        val before = arguments.toString()

        val result = backend.operation(project, "list", arguments = arguments)

        assertTrue(result.getBoolean("ok"))
        assertEquals(emptyList<String>(), workspaceIds(result))
        assertEquals(0, result.getInt("total_count"))
        assertFalse(result.getBoolean("truncated"))
        assertFalse(result.getBoolean("shell_executed"))
        assertEquals(0, executions)
        assertEquals(before, arguments.toString())
        assertFalse(ledger.exists())
    }

    @Test
    fun listInjectsExactScopedIdsAndDefensivelyFiltersRowsAndUnavailableIds() {
        val otherEnvironmentId = "d".repeat(32)
        val otherProjectId = "e".repeat(32)
        store().rememberAll(owner, "debian", project, linkedSetOf(nextId, id))
        store().remember(otherOwner, "debian", project, foreignId)
        store().remember(owner, "alpine", project, otherEnvironmentId)
        store().remember(owner, "debian", "/workspace/Other", otherProjectId)
        val arguments = JSONObject().put("workspace_ids", JSONArray().put(foreignId))
            .put("project", "/workspace/Other").put("action", "prepare").put("limit", 10)
        val before = arguments.toString()
        var executions = 0
        val backend = backend { request ->
            executions++
            assertEquals("list", request.getString("action"))
            assertEquals(listOf(id, nextId), strings(request.getJSONArray("workspace_ids")))
            assertEquals(10, request.getInt("limit"))
            terminal(JSONObject().put("ok", true).put("workspaces", JSONArray()
                .put(prepared(nextId))
                .put(prepared(foreignId).put("owner", owner))
                .put(7).put(JSONObject().put("state", "ready"))
                .put(prepared(id)).put(prepared(otherEnvironmentId)).put(prepared(otherProjectId)))
                .put("unavailable_workspace_ids", JSONArray()
                    .put(foreignId).put(id).put(otherEnvironmentId).put(nextId).put(otherProjectId)))
        }

        val result = backend.operation(project, "list", arguments = arguments)

        assertTrue(result.getBoolean("ok"))
        assertEquals(listOf(nextId, id), workspaceIds(result))
        assertEquals(listOf(id, nextId), strings(result.getJSONArray("unavailable_workspace_ids")))
        assertEquals(1, executions)
        assertEquals(before, arguments.toString())
        assertEquals(setOf(id, nextId), store().ids(owner, "debian", project))
        assertEquals(setOf(foreignId), store().ids(otherOwner, "debian", project))
    }

    @Test
    fun corruptLedgerBlocksPrepareListAndOwnedOperationsBeforeExecutor() {
        ledger.writeText("{broken-private-ownership-ledger")
        val before = ledger.readBytes()
        var executions = 0
        val backend = backend(legacyIds = { setOf(id) }) {
            executions++
            terminal(prepared())
        }

        for (action in listOf("prepare", "list", "inspect", "merge", "discard", "read", "begin_review")) {
            assertFailure(backend.operation(project, action, id), "WORKSPACE_OWNERSHIP_STORE_UNAVAILABLE")
        }
        assertThrows(WorkspaceOwnershipException::class.java) { backend.ownsWorkspace(project, id) }
        assertEquals(0, executions)
        assertArrayEquals(before, ledger.readBytes())
    }

    @Test
    fun prepareWithMismatchedOrMissingEnvironmentEvidenceDoesNotClaimWorkspace() {
        var executions = 0
        for (reportedEnvironment in listOf("alpine", "")) {
            val backend = backend { request ->
                executions++
                assertEquals("prepare", request.getString("action"))
                if (reportedEnvironment.isEmpty()) {
                    AgentModelClient.ToolResult(JSONObject().put("ok", true).put("exit_code", 0)
                        .put("stdout", prepared().toString()).toString())
                } else {
                    terminal(prepared(), environment = reportedEnvironment)
                }
            }
            assertFailure(backend.operation(project, "prepare"), "WORKSPACE_ENVIRONMENT_CHANGED", executed = true)
            assertTrue(store().ids(owner, "debian", project).isEmpty())
            assertTrue(store().ids(owner, "alpine", project).isEmpty())
            assertFalse(backend.ownsWorkspace(project, id))
        }
        assertEquals(2, executions)
        assertFalse(ledger.exists())
        val reopened = backend { error("Rejected prepare must not authorize later access") }
        assertFailure(reopened.operation(project, "inspect", id), "WORKSPACE_NOT_OWNED")
    }

    @Test
    fun trustedLegacyBackfillSurvivesReopeningWithoutLegacyHistory() {
        var legacyReads = 0
        var executions = 0
        val legacyBackend = backend(legacyIds = { requestedProject ->
            legacyReads++
            assertEquals(project, requestedProject)
            linkedSetOf(id, nextId)
        }) { request ->
            executions++
            assertEquals("inspect", request.getString("action"))
            assertEquals(id, request.getString("workspace_id"))
            terminal(prepared())
        }

        assertTrue(legacyBackend.requireOperation(project, "inspect", id).getBoolean("ok"))
        assertEquals(1, legacyReads)
        assertEquals(setOf(id, nextId), store().ids(owner, "debian", project))
        for (action in listOf("inspect", "merge", "discard")) {
            val reopened = backend(legacyIds = { emptySet() }) { request ->
                executions++
                assertEquals(action, request.getString("action"))
                assertEquals(nextId, request.getString("workspace_id"))
                terminal(prepared(nextId))
            }
            assertTrue(reopened.requireOperation(project, action, nextId).getBoolean("ok"))
        }
        assertEquals(4, executions)
        assertEquals(1, legacyReads)
        val stranger = backend(ownerId = otherOwner) { error("Backfill is not a transfer of ownership") }
        assertFailure(stranger.operation(project, "inspect", nextId), "WORKSPACE_NOT_OWNED")
    }

    @Test
    fun conflictingLegacyBackfillFailsClosedWithoutPartiallyClaimingIds() {
        store().remember(otherOwner, "debian", project, id)
        val before = ledger.readBytes()
        var executions = 0
        val backend = backend(legacyIds = { linkedSetOf(nextId, id) }) {
            executions++
            terminal(prepared())
        }

        assertFailure(backend.operation(project, "list"), "WORKSPACE_OWNERSHIP_STORE_UNAVAILABLE")
        assertEquals(0, executions)
        assertArrayEquals(before, ledger.readBytes())
        assertTrue(store().ids(owner, "debian", project).isEmpty())
        assertEquals(setOf(id), store().ids(otherOwner, "debian", project))
    }

    @Test
    fun successfulPrepareResponseCannotRebindAnExistingOwnersId() {
        store().remember(otherOwner, "debian", project, id)
        val before = ledger.readBytes()
        var executions = 0
        val result = backend { request ->
            executions++
            assertEquals("prepare", request.getString("action"))
            terminal(prepared())
        }.operation(project, "prepare")

        assertFailure(result, "WORKSPACE_OWNERSHIP_RECORD_FAILED", executed = true)
        assertEquals(id, result.getString("workspace_id"))
        assertEquals(1, executions)
        assertArrayEquals(before, ledger.readBytes())
        assertTrue(store().ids(owner, "debian", project).isEmpty())
        assertEquals(setOf(id), store().ids(otherOwner, "debian", project))
    }

    @Test
    fun ownedWorkspaceReviewRefusalsKeepTheirOriginalCodes() {
        store().remember(owner, "debian", project, id)
        val before = ledger.readBytes()
        var executions = 0
        val refusals = listOf(
            "merge" to "REVIEW_REQUIRED",
            "merge" to "PROJECT_MOVED_REVIEW_AGAIN",
            "review" to "WORKSPACE_CHANGED",
            "begin_review" to "WORKSPACE_NOT_READY",
        )
        for ((action, code) in refusals) {
            val backend = backend { request ->
                executions++
                assertEquals(action, request.getString("action"))
                assertEquals(id, request.getString("workspace_id"))
                terminal(JSONObject().put("ok", false).put("code", code).put("reason", "review gate"))
            }
            val result = backend.operation(project, action, id)
            assertFalse(result.getBoolean("ok"))
            assertEquals(code, result.getString("code"))
            assertEquals("review gate", result.getString("reason"))
            val error = assertThrows(WorkspaceOperationException::class.java) {
                backend.requireOperation(project, action, id)
            }
            assertEquals(code, error.code)
        }
        assertEquals(refusals.size * 2, executions)
        assertArrayEquals(before, ledger.readBytes())
    }
}
