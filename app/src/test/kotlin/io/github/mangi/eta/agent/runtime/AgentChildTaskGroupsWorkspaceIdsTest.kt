package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentChildTaskGroupsWorkspaceIdsTest {
    private val registry = AgentChildTaskGroups
    private val workspaceId = "0123456789abcdef0123456789abcdef"

    @Suppress("UNCHECKED_CAST")
    private fun groups(): MutableMap<String, Any> = registry.javaClass.getDeclaredField("groups")
        .apply { isAccessible = true }.get(registry) as MutableMap<String, Any>

    private fun install(ownerId: String, coordinator: SubAgentCoordinator? = null): Pair<String, Any> {
        val generation = UUID.randomUUID().toString()
        val groupClass = registry.javaClass.declaredClasses.single { it.simpleName == "Group" }
        val constructor = groupClass.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val group = constructor.newInstance(ownerId, "test-run", generation, "test-lease", coordinator, null, emptyList<Any>(), null)
        synchronized(registry) { groups()[generation] = group }
        return generation to group
    }

    private fun set(group: Any, field: String, value: Any?) {
        group.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(group, value)
    }

    private fun record(project: String, id: String): String = JSONObject()
        .put("ok", true).put("project", project).put("workspace_id", id)
        .put("workspace_ownership_verified", true).toString()

    @Test fun liveTaskAndArchivedSnapshotExposeOnlyTheirOwnersProject() {
        val project = "/workspace/sample"
        val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")
        val coordinator = SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" }
        val (generation, group) = install("workspace-owner", coordinator)
        try {
            val start = JSONObject(coordinator.execute(AgentModelClient.ToolCall("start", "delegate_task",
                JSONObject().put("task", "check").put("project", project).toString())).content)
            val taskId = start.getString("task_id")
            val finished = JSONObject(coordinator.execute(AgentModelClient.ToolCall("finish", "get_task_result",
                JSONObject().put("task_id", taskId).put("wait_ms", 10000).toString())).content)
            assertEquals("completed", finished.getString("status"))
            @Suppress("UNCHECKED_CAST")
            val tasks = coordinator.javaClass.getDeclaredField("tasks").apply { isAccessible = true }
                .get(coordinator) as Map<String, Any>
            val task = requireNotNull(tasks[taskId])
            task.javaClass.getDeclaredField("workspaceId").apply { isAccessible = true }.set(task, workspaceId)
            assertTrue(registry.ownedWorkspaceIds("workspace-owner", project).isEmpty())
            // This fixture explicitly models runtime-verified ownership, not just a supplied ID.
            task.javaClass.getDeclaredField("workspaceOwnershipVerified").apply { isAccessible = true }.set(task, true)
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds("workspace-owner", project))
            assertTrue(registry.ownsWorkspace("workspace-owner", project, workspaceId))
            assertTrue(registry.ownsWorkspace("workspace-owner", project, null))
            assertTrue(registry.ownedWorkspaceIds("other-owner", project).isEmpty())
            assertTrue(registry.ownedWorkspaceIds("workspace-owner", "/workspace/other").isEmpty())

            val dto = JSONObject(coordinator.execute(AgentModelClient.ToolCall("archive", "get_task_result",
                JSONObject().put("task_id", taskId).toString())).content)
            val snapshot = registry.javaClass.getDeclaredMethod("archiveSnapshot", JSONObject::class.java)
                .apply { isAccessible = true }.invoke(registry, dto) as String
            assertEquals(true, JSONObject(snapshot).get("workspace_ownership_verified"))
            synchronized(registry) {
                set(group, "snapshots", mapOf(taskId to snapshot))
                set(group, "coordinator", null)
            }
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds("workspace-owner", project))
            assertTrue(registry.ownsWorkspace("workspace-owner", project, workspaceId))
            assertFalse(registry.ownsWorkspace("other-owner", project, workspaceId))
            assertFalse(registry.ownsWorkspace("workspace-owner", "/workspace/other", workspaceId))
        } finally {
            synchronized(registry) { groups().remove(generation) }
            coordinator.close()
        }
    }

    @Test fun backfillRequiresAnExplicitMatchingLinuxEnvironment() {
        val (generation, group) = install("namespace-owner")
        try {
            synchronized(registry) {
                set(group, "snapshots", mapOf("task" to record("/workspace/sample", workspaceId)))
            }
            assertTrue(registry.ownedWorkspaceIds("namespace-owner", "/workspace/sample", "debian").isEmpty())
            synchronized(registry) { set(group, "workspaceEnvironment", "debian") }
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds("namespace-owner", "/workspace/sample", "debian"))
            assertTrue(registry.ownedWorkspaceIds("namespace-owner", "/workspace/sample", "alpine").isEmpty())
        } finally { synchronized(registry) { groups().remove(generation) } }
    }

    @Test fun missingFalseAndNonBooleanProofNeverAuthorizeRegardlessOfRoleOrStatus() {
        val (generation, group) = install("unverified-owner")
        try {
            for (role in listOf("implementation", "review", "summary", "research")) {
                for (status in listOf("queued", "running", "completed", "failed")) {
                    for (proof in listOf(null, false, "true", 1, JSONObject.NULL)) {
                        val dto = JSONObject().put("ok", true).put("project", "project-a")
                            .put("workspace_id", workspaceId).put("role", role).put("status", status)
                        if (proof != null) dto.put("workspace_ownership_verified", proof)
                        val snapshot = registry.javaClass.getDeclaredMethod("archiveSnapshot", JSONObject::class.java)
                            .apply { isAccessible = true }.invoke(registry, dto) as String
                        synchronized(registry) { set(group, "snapshots", mapOf("task" to snapshot)) }
                        assertTrue("role=$role status=$status proof=$proof",
                            registry.ownedWorkspaceIds("unverified-owner", "project-a").isEmpty())
                    }
                }
            }
        } finally { synchronized(registry) { groups().remove(generation) } }
    }

    @Test fun malformedAndForeignSnapshotsNeverAuthorizeWorkspace() {
        val (generation, group) = install("owner-a")
        val (otherGeneration, otherGroup) = install("owner-b")
        try {
            val badIds = listOf("", " ", "ws-a", "0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdeg",
                workspaceId.uppercase(), "01234567-89ab-cdef-0123-456789abcdef")
            synchronized(registry) {
                set(group, "snapshots", (badIds.mapIndexed { index, id -> "bad-$index" to record("project-a", id) } +
                    listOf("invalid-json" to "{", "failed" to JSONObject(record("project-a", workspaceId)).put("ok", false).toString(),
                        "null" to JSONObject().put("ok", true).put("project", "project-a").put("workspace_id", JSONObject.NULL).toString(),
                        "missing" to JSONObject().put("ok", true).put("project", "project-a").toString(),
                        "foreign-project" to record("project-b", workspaceId))).toMap())
                set(otherGroup, "snapshots", mapOf("other" to record("project-a", workspaceId)))
            }
            assertTrue(registry.ownedWorkspaceIds("owner-a", "project-a").isEmpty())
            assertFalse(registry.ownsWorkspace("owner-a", "project-a", null))
            assertFalse(registry.ownsWorkspace("owner-a", "project-a", workspaceId))
            assertFalse(registry.ownsWorkspace("owner-a", "project-a", "ws-a"))
            assertEquals(setOf(workspaceId), registry.ownedWorkspaceIds("owner-b", "project-a"))
            assertTrue(registry.ownedWorkspaceIds("owner-a", "").isEmpty())
        } finally {
            synchronized(registry) { groups().remove(generation); groups().remove(otherGeneration) }
        }
    }
}
