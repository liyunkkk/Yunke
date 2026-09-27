package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.delegation.SubAgentWorkspace
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real Coordinator -> Groups legacy lookup -> scoped backend; only terminal execution is faked. */
class AgentChildTaskWorkspaceAuthorizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val registry = AgentChildTaskGroups
    private val owner = "1".repeat(32)
    private val workspaceId = "a".repeat(32)
    private val project = "/workspace/sample"
    private val script = "authorization-test-script"
    private val model = AgentModelClient.ModelConfig(
        baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")

    @Suppress("UNCHECKED_CAST")
    private fun groups(): MutableMap<String, Any> = registry.javaClass.getDeclaredField("groups")
        .apply { isAccessible = true }.get(registry) as MutableMap<String, Any>

    private fun set(group: Any, field: String, value: Any?) {
        group.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(group, value)
    }

    private fun install(coordinator: SubAgentCoordinator): Pair<String, Any> {
        val generation = UUID.randomUUID().toString()
        val constructor = registry.javaClass.declaredClasses.single { it.simpleName == "Group" }
            .declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val group = constructor.newInstance(owner, "test-run", generation, "test-lease", coordinator,
            null, emptyList<Any>(), null)
        synchronized(registry) {
            set(group, "workspaceEnvironment", "debian")
            groups()[generation] = group
        }
        return generation to group
    }

    private fun archive(group: Any, dto: JSONObject): JSONObject {
        val snapshot = registry.javaClass.getDeclaredMethod("archiveSnapshot", JSONObject::class.java)
            .apply { isAccessible = true }.invoke(registry, dto) as String
        synchronized(registry) {
            set(group, "snapshots", mapOf(dto.getString("task_id") to snapshot))
            set(group, "coordinator", null)
        }
        return JSONObject(snapshot)
    }

    private fun backend(store: AgentWorkspaceOwnershipStore, terminalCalls: AtomicInteger,
        legacy: (String) -> Set<String> = { registry.ownedWorkspaceIds(owner, it, "debian") },
    ) = SubAgentWorkspace(script, SubAgentWorkspace.Ownership(owner, store, { "debian" }, legacy),
        AgentModelClient.ToolExecutor { call ->
            terminalCalls.incrementAndGet()
            assertEquals("terminal", call.name)
            val command = JSONObject(call.argumentsJson).getString("command")
            val quoted = command.substringAfter("python3 -I -c '$script' ")
            val request = JSONObject(quoted.substring(1, quoted.length - 1).replace("'\"'\"'", "'"))
            val result = JSONObject().put("ok", true).put("id", workspaceId)
                .put("path", "$project/.agent/worktrees/$workspaceId")
                .put("state", if (request.getString("action") == "begin_review") "reviewing" else "sealed")
            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("exit_code", 0)
                .put("environment", "debian").put("stdout", result.toString()).toString())
        })

    private fun coordinator(role: String, backend: SubAgentWorkspace, childCalls: AtomicInteger) =
        SubAgentCoordinator(listOf(model), roles = listOf(if (role == "summary") "review" else role),
            workspace = backend, executeWorkspaceChild = { _, _, _, _, _, _ ->
                childCalls.incrementAndGet(); "done"
            }) { _, _, _ -> error("Expected a workspace child") }

    private fun start(coordinator: SubAgentCoordinator, role: String, id: String? = null): JSONObject =
        JSONObject(coordinator.execute(AgentModelClient.ToolCall("start", "delegate_task", JSONObject()
            .put("task", "check workspace").put("role", role).put("project", project)
            .apply { if (id != null) put("workspace_id", id) }
            // Attempting to inject the DTO field must not initialize runtime proof.
            .put("workspace_ownership_verified", true).toString())).content)

    private fun snapshot(coordinator: SubAgentCoordinator, id: String, wait: Boolean = false) =
        JSONObject(coordinator.execute(AgentModelClient.ToolCall("result", "get_task_result",
            JSONObject().put("task_id", id).put("wait_ms", if (wait) 10000 else 0).toString())).content)

    private fun assertUnownedCannotSelfAuthorize(alpineOnly: Boolean) {
        val privateFiles = temporary.newFolder("private-files")
        val store = AgentWorkspaceOwnershipStore(privateFiles)
        if (alpineOnly) store.remember(owner, "alpine", project, workspaceId)
        val ledger = File(privateFiles, "agent-workspace-ownership.json")
        val before = if (ledger.exists()) ledger.readBytes() else null
        // All roles that accept a caller-supplied ID must cross the same authorization boundary.
        for (role in listOf("review", "research", "summary")) {
            val terminalCalls = AtomicInteger()
            val childCalls = AtomicInteger()
            val proofsDuringLookup = ConcurrentLinkedQueue<Boolean>()
            lateinit var coordinator: SubAgentCoordinator
            val backend = backend(store, terminalCalls) { requestedProject ->
                proofsDuringLookup.add(snapshot(coordinator, coordinator.taskIds().single())
                    .opt("workspace_ownership_verified") == true)
                registry.ownedWorkspaceIds(owner, requestedProject, "debian")
            }
            coordinator = coordinator(role, backend, childCalls)
            val (generation, group) = install(coordinator)
            try {
                val started = start(coordinator, role, workspaceId)
                assertEquals(false, started.get("workspace_ownership_verified"))
                val finished = snapshot(coordinator, started.getString("task_id"), wait = true)
                assertEquals("failed", finished.getString("status"))
                assertEquals("WORKSPACE_NOT_OWNED", finished.getString("error_code"))
                assertEquals(false, finished.get("workspace_ownership_verified"))
                assertTrue(proofsDuringLookup.isNotEmpty())
                assertTrue(proofsDuringLookup.none { it })
                assertEquals(0, terminalCalls.get())
                assertEquals(0, childCalls.get())
                assertTrue(registry.ownedWorkspaceIds(owner, project, "debian").isEmpty())
                assertEquals(false, archive(group, finished).get("workspace_ownership_verified"))
                assertFalse(backend.ownsWorkspace(project, workspaceId))
                assertTrue(AgentWorkspaceOwnershipStore(privateFiles).ids(owner, "debian", project).isEmpty())
                assertEquals(if (alpineOnly) setOf(workspaceId) else emptySet<String>(),
                    store.ids(owner, "alpine", project))
                if (before == null) assertFalse(ledger.exists()) else assertArrayEquals(before, ledger.readBytes())
                assertEquals(0, terminalCalls.get())
            } finally {
                synchronized(registry) { groups().remove(generation) }
                coordinator.close()
            }
        }
    }

    @Test fun unregisteredReviewIdCannotBeBackfilledFromItsOwnTask() {
        assertUnownedCannotSelfAuthorize(alpineOnly = false)
    }

    @Test fun alpineOnlyReviewIdCannotBeBackfilledIntoDebianFromItsOwnTask() {
        assertUnownedCannotSelfAuthorize(alpineOnly = true)
    }

    @Test fun successfulPrepareCreatesTrustedLiveAndArchivedEvidenceForBackfill() {
        val store = AgentWorkspaceOwnershipStore(temporary.newFolder("prepared"))
        val terminalCalls = AtomicInteger()
        val childCalls = AtomicInteger()
        val coordinator = coordinator("implementation", backend(store, terminalCalls), childCalls)
        val (generation, group) = install(coordinator)
        try {
            val started = start(coordinator, "implementation")
            assertEquals(true, started.get("workspace_ownership_verified"))
            assertEquals(workspaceId, started.getString("workspace_id"))
            val finished = snapshot(coordinator, started.getString("task_id"), wait = true)
            assertEquals("completed", finished.getString("status"))
            assertEquals(1, childCalls.get())
            assertEquals(2, terminalCalls.get()) // prepare and automatic seal
            assertEquals(setOf(workspaceId), store.ids(owner, "debian", project))
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds(owner, project, "debian"))
            assertEquals(true, archive(group, finished).get("workspace_ownership_verified"))
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds(owner, project, "debian"))

            val migratedStore = AgentWorkspaceOwnershipStore(temporary.newFolder("backfilled"))
            val backfillTerminalCalls = AtomicInteger()
            assertTrue(migratedStore.ids(owner, "debian", project).isEmpty())
            assertTrue(backend(migratedStore, backfillTerminalCalls).ownsWorkspace(project, workspaceId))
            assertEquals(setOf(workspaceId), migratedStore.ids(owner, "debian", project))
            assertEquals(0, backfillTerminalCalls.get())
        } finally {
            synchronized(registry) { groups().remove(generation) }
            coordinator.close()
        }
    }

    @Test fun successfulScopedBeginReviewCreatesProofOnlyAfterAuthorization() {
        val store = AgentWorkspaceOwnershipStore(temporary.newFolder("reviewed"))
        store.remember(owner, "debian", project, workspaceId)
        val terminalCalls = AtomicInteger()
        val childCalls = AtomicInteger()
        val proofsDuringLookup = ConcurrentLinkedQueue<Boolean>()
        lateinit var coordinator: SubAgentCoordinator
        val backend = backend(store, terminalCalls) { requestedProject ->
            proofsDuringLookup.add(snapshot(coordinator, coordinator.taskIds().single())
                .opt("workspace_ownership_verified") == true)
            registry.ownedWorkspaceIds(owner, requestedProject, "debian")
        }
        coordinator = coordinator("review", backend, childCalls)
        val (generation, group) = install(coordinator)
        try {
            val started = start(coordinator, "review", workspaceId)
            val finished = snapshot(coordinator, started.getString("task_id"), wait = true)
            assertEquals("completed", finished.getString("status"))
            assertEquals(false, proofsDuringLookup.peek())
            assertEquals(true, finished.get("workspace_ownership_verified"))
            assertEquals(2, terminalCalls.get()) // begin_review and review
            assertEquals(1, childCalls.get())
            assertEquals(true, archive(group, finished).get("workspace_ownership_verified"))
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds(owner, project, "debian"))
        } finally {
            synchronized(registry) { groups().remove(generation) }
            coordinator.close()
        }
    }
}
