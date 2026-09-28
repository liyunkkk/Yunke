package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Regression: a parent whose earlier generation was archived (coordinator released, only
 * read-only snapshots left) must still be able to delegate through its live current generation.
 * Before the fix every ordinary delegate_task was routed to the archived group and answered
 * TASK_FINISHED, so the owner could never delegate again in that conversation.
 */
class ArchivedGenerationDispatchTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(30)

    private val registry = AgentChildTaskGroups
    private val model = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "live", systemPrompt = "",
    )

    @Suppress("UNCHECKED_CAST")
    private fun groups() = registry.javaClass.getDeclaredField("groups").apply { isAccessible = true }
        .get(registry) as MutableMap<String, Any>
    private fun set(group: Any, name: String, value: Any?) =
        group.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(group, value)

    private fun install(owner: String, generation: String, coordinator: SubAgentCoordinator?): Any {
        val type = registry.javaClass.declaredClasses.single { it.simpleName == "Group" }
        val ctor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val group = ctor.newInstance(owner, "run-$generation", generation, "fixture-lease", coordinator, {}, emptyList<Any>(), null)
        set(group, "leaseHeld", false)
        synchronized(registry) { groups()[generation] = group }
        return group
    }

    private fun delegate(owner: String, generation: String) = JSONObject(registry.execute(
        owner, generation,
        AgentModelClient.ToolCall("fixture", "delegate_task", JSONObject().put("task", "inspect").toString()),
    ).content)

    @Test
    fun archivedEarlierGenerationDoesNotSwallowNewDelegation() {
        val owner = UUID.randomUUID().toString()
        val archivedGeneration = UUID.randomUUID().toString()
        val currentGeneration = UUID.randomUUID().toString()
        val archived = install(owner, archivedGeneration, coordinator = null)
        set(archived, "attached", false)
        set(archived, "snapshots", mapOf("old-task" to JSONObject().put("ok", true).put("task_id", "old-task")
            .put("status", "completed").put("archived", true).toString()))
        val live = SubAgentCoordinator(listOf(model)) { _, _, _ -> "fresh result" }
        install(owner, currentGeneration, live)
        try {
            val response = delegate(owner, currentGeneration)
            assertNotEquals("TASK_FINISHED", response.optString("code"))
            assertTrue(response.toString(), response.optBoolean("ok"))
            val id = response.getString("task_id")
            assertTrue("new task must belong to the live coordinator", live.ownsTask(id))
            // The archived snapshot stays readable and unchanged.
            val old = JSONObject(registry.execute(owner, currentGeneration, AgentModelClient.ToolCall(
                "fixture", "get_task_result", JSONObject().put("task_id", "old-task").toString())).content)
            assertEquals("completed", old.getString("status"))
        } finally {
            live.close()
            synchronized(registry) {
                groups().remove(archivedGeneration)
                groups().remove(currentGeneration)
            }
        }
    }

    @Test
    fun archivedGenerationAloneStillRefusesWithoutConfigurationFallback() {
        val owner = UUID.randomUUID().toString()
        val archivedGeneration = UUID.randomUUID().toString()
        val archived = install(owner, archivedGeneration, coordinator = null)
        set(archived, "attached", false)
        set(archived, "snapshots", mapOf("old-task" to JSONObject().put("task_id", "old-task").toString()))
        try {
            // No live current generation: never silently reuse the archived group or a new setting.
            assertEquals("RUN_CLOSED", delegate(owner, "missing-generation").getString("code"))
        } finally {
            synchronized(registry) { groups().remove(archivedGeneration) }
        }
    }
}
