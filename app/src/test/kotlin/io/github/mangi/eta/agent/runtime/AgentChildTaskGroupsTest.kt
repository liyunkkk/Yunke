package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentChildTaskGroupsTest {
    // Detached records need no Android service or child backend.
    private val owner = AgentChildTaskGroups

    @Suppress("UNCHECKED_CAST")
    private fun groups(): MutableMap<String, Any> {
        val field = owner.javaClass.getDeclaredField("groups").apply { isAccessible = true }
        return field.get(owner) as MutableMap<String, Any>
    }

    private fun install(ownerId: String): Pair<String, Any> {
        val generation = UUID.randomUUID().toString()
        val groupClass = owner.javaClass.declaredClasses.single { it.simpleName == "Group" }
        val constructor = groupClass.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val group = constructor.newInstance(ownerId, "test-run", generation, "test-lease", null, null, emptyList<Any>(), null)
        synchronized(owner) { groups()[generation] = group }
        return generation to group
    }

    private fun set(group: Any, field: String, value: Any) {
        group.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(group, value)
    }

    private fun record(id: String, project: String, workspace: String) = JSONObject()
        .put("ok", true).put("task_id", id).put("project", project).put("workspace_id", workspace)
        .put("status", "failed").toString()

    @Test
    fun archivedWorkspaceAuthorizationIsOwnerProjectAndWorkspaceScoped() {
        val (generation, group) = install("owner-a")
        try {
            val snapshot = record("task-a", "project-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            synchronized(owner) { set(group, "snapshots", mapOf("task-a" to snapshot)) }
            assertFalse(owner.ownsWorkspace("owner-a", "project-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            assertFalse(owner.ownsWorkspace("owner-a", "project-a", null))

            val verifiedSnapshot = JSONObject(snapshot).put("workspace_ownership_verified", true).toString()
            synchronized(owner) { set(group, "snapshots", mapOf("task-a" to verifiedSnapshot)) }
            assertTrue(owner.ownsWorkspace("owner-a", "project-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            assertTrue(owner.ownsWorkspace("owner-a", "project-a", null))
            assertFalse(owner.ownsWorkspace("owner-b", "project-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            assertFalse(owner.ownsWorkspace("owner-a", "project-b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            assertFalse(owner.ownsWorkspace("owner-a", "project-a", "ws-b"))
            assertFalse(owner.ownsWorkspace("owner-a", "project-a", ""))
        } finally { synchronized(owner) { groups().remove(generation) } }
    }

    @Test
    fun retiringRecordReturnsExplicitRetryNotTaskNotFoundUntilSnapshotArrives() {
        val (generation, group) = install("handoff-owner")
        val id = "handoff-task"
        val call = AgentModelClient.ToolCall("get", "get_task_result", JSONObject().put("task_id", id).toString())
        val resultMethod = owner.javaClass.declaredMethods.single { it.name == "result" }.apply { isAccessible = true }
        try {
            set(group, "retiring", true)
            val pending = resultMethod.invoke(owner, group, id, call) as AgentModelClient.ToolResult
            assertEquals("TASK_RESULT_PENDING", JSONObject(pending.content).getString("code"))
            synchronized(owner) { set(group, "snapshots", mapOf(id to record(id, "project", "workspace"))) }
            val archived = resultMethod.invoke(owner, group, id, call) as AgentModelClient.ToolResult
            assertEquals(id, JSONObject(archived.content).getString("task_id"))
        } finally { synchronized(owner) { groups().remove(generation) } }
    }

    @Test
    fun generationCleanupRemovesClaimsEvenWithoutArchivedSnapshotKeys() {
        val (generation, group) = install("claim-owner")
        val claimClass = owner.javaClass.declaredClasses.single { it.simpleName == "Claim" }
        val claimCtor = claimClass.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val claims = owner.javaClass.getDeclaredField("claimed").apply { isAccessible = true }.get(owner) as MutableMap<String, Any>
        val forget = owner.javaClass.declaredMethods.single { it.name == "forgetGeneration" }.apply { isAccessible = true }
        val predecessor = "predecessor-${UUID.randomUUID()}"
        try {
            synchronized(owner) {
                claims[predecessor] = claimCtor.newInstance(generation, "successor-generation", "unknown")
                forget.invoke(owner, generation)
                assertFalse(claims.containsKey(predecessor))
            }
        } finally { synchronized(owner) { claims.remove(predecessor); groups().remove(generation) } }
    }
    @Test
    fun retiringSuccessorKeepsSuccessfulAndUnknownPredecessorClaims() {
        val (generation, _) = install("claim-retention-owner")
        val claimClass = owner.javaClass.declaredClasses.single { it.simpleName == "Claim" }
        val constructor = claimClass.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val claims = owner.javaClass.getDeclaredField("claimed").apply { isAccessible = true }.get(owner) as MutableMap<String, Any>
        val forget = owner.javaClass.declaredMethods.single { it.name == "forgetGeneration" }.apply { isAccessible = true }
        val predecessor = "retained-${UUID.randomUUID()}"
        val successor = "successor-${UUID.randomUUID()}"
        try {
            synchronized(owner) {
                for (outcome in listOf("unknown", "successor-task")) {
                    claims[predecessor] = constructor.newInstance(generation, successor, outcome)
                    forget.invoke(owner, successor)
                    assertTrue("closing successor must not permit replay: $outcome", claims.containsKey(predecessor))
                    forget.invoke(owner, generation)
                    assertFalse(claims.containsKey(predecessor))
                }
            }
        } finally { synchronized(owner) { claims.remove(predecessor); groups().remove(generation) } }
    }

}
