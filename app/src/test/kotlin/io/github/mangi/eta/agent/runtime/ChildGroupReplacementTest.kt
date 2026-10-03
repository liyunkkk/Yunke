package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class ChildGroupReplacementTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "old", systemPrompt = "", providerId = "provider-a")
    private fun worker(owner: String, id: String = "stable", revision: String = "old", provider: String = "provider-a") =
        AgentChildTaskGroups.Worker(id, "review", provider, ChildTaskConfigPolicy.Candidate(
            ChildTaskConfigPolicy.WorkerKey(owner, id, "review"), ChildTaskConfigPolicy.Availability.AVAILABLE,
            revision, ChildWorkerConfigResolver.Configuration(SubAgentProfile(id, id, "review", providerId = provider, modelId = revision),
                model.copy(model = revision, providerId = provider))))
    private fun snapshot() = JSONObject().put("ok", true).put("task_id", "old-task").put("agent_id", "stable")
        .put("role", "research").put("status", "failed").put("error_code", "SUB_AGENT_FAILED")
        .put("provider_id", "provider-a").put("workspace_id", JSONObject.NULL).put("workspace_path", "")
        .put("execution_stopped", true).put("can_replace", true).put("handoff_version", 7)
        .put("supervision", JSONObject().put("checkpoint", "checked source"))

    @Test fun sameWorkerNeedsChangedRevisionCurrentHandoffAndRealChildFault() {
        val old = worker("owner")
        val next = worker("owner", revision = "new")
        fun select(json: JSONObject = snapshot(), read: Long? = 7, current: AgentChildTaskGroups.Worker = next) =
            ChildTaskReplacementSelection.choose("owner", "old-generation", listOf(old), listOf(current), json, JSONObject(), read, false)
        assertEquals(0, select().index)
        assertEquals("CONFIGURATION_UNCHANGED", select(current = old).error)
        assertEquals("HANDOFF_NOT_READ", select(read = 6).error)
        assertEquals("HANDOFF_NOT_READ", select(read = null).error)
        assertEquals("STOP_NOT_CONFIRMED", select(snapshot().put("execution_stopped", false)).error)
        assertEquals("CHILD_NOT_FAULTY", select(snapshot().put("status", "cancelled")).error)
        assertEquals("CHILD_NOT_FAULTY", select(snapshot().put("status", "awaiting_decision")).error)
        assertEquals("WORKSPACE_REVIEW_REQUIRED", select(snapshot().put("workspace_id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).error)
        // JSONObject.NULL must not become a phantom workspace string on Android.
        assertNull(select(snapshot().put("workspace_id", JSONObject.NULL)).error)
    }

    @Test fun stoppedNoProgressPauseIsEligibleButManualPauseIsNot() {
        val stalled = snapshot().put("status", "awaiting_decision").put("error_code", "SUB_AGENT_NO_PROGRESS")
        assertTrue(ChildTaskReplacementSelection.eligibleStatus(stalled))
        assertFalse(ChildTaskReplacementSelection.eligibleStatus(snapshot().put("status", "awaiting_decision")))
        assertFalse(ChildTaskReplacementSelection.eligibleStatus(snapshot().put("status", "cancelled")))
        val selected = ChildTaskReplacementSelection.choose("owner", "old-generation", listOf(worker("owner")),
            listOf(worker("owner", revision = "new")), stalled, JSONObject(), 7, false)
        assertEquals(0, selected.index)
        assertEquals("STOP_NOT_CONFIRMED", ChildTaskReplacementSelection.choose("owner", "old-generation",
            listOf(worker("owner")), listOf(worker("owner", revision = "new")),
            stalled.put("execution_stopped", false), JSONObject(), 7, false).error)
    }

    @Test fun explicitOtherWorkerKeepsProviderIsolationButNoFirstAvailableFallback() {
        val old = worker("owner")
        val alternate = AgentChildTaskGroups.Worker("other", "review", "provider-b")
        fun select(args: JSONObject, current: AgentChildTaskGroups.Worker = alternate) = ChildTaskReplacementSelection.choose(
            "owner", "generation", listOf(old), listOf(current), snapshot(), args, 7, false)
        assertEquals(0, select(JSONObject().put("agent_id", "other")).index)
        assertEquals("AGENT_NOT_CONFIGURED", select(JSONObject()).error)
        assertEquals("REPLACEMENT_PROVIDER_UNAVAILABLE", select(JSONObject().put("agent_id", "other"), alternate.copy(providerId = "provider-a")).error)
        assertEquals("WORKER_ID_MISMATCH", select(JSONObject().put("agent_id", "other").put("worker", 2)).error)
    }

    @Test fun handoffVersionChangesOnlyOnEvidenceAndInternalObserveDoesNotReadIt() {
        val handoffs = ChildTaskHandoff()
        val first = handoffs.observe(snapshot().put("execution_exited", false))
        val version = first.getLong("handoff_version")
        assertFalse(first.getBoolean("execution_stopped")) // A caller-supplied true is not authoritative.
        assertFalse(handoffs.matchesRead("old-task", version))
        handoffs.recordRead(first)
        assertTrue(handoffs.matchesRead("old-task", version))
        val heartbeat = handoffs.observe(snapshot().put("execution_exited", false)
            .put("supervision", JSONObject().put("checkpoint", "checked source").put("latest_seq", 800)))
        assertEquals(version, heartbeat.getLong("handoff_version"))
        val stopped = handoffs.observe(snapshot().put("execution_exited", true))
        assertTrue(stopped.getLong("handoff_version") > version)
        assertFalse(handoffs.matchesRead("old-task", stopped.getLong("handoff_version")))
        handoffs.recordRead(stopped)
        assertTrue(handoffs.matchesRead("old-task", stopped.getLong("handoff_version")))
    }

    @Test fun errorDetailsAreAddedToJsonBeforeWrappingToolResult() {
        val method = AgentChildTaskGroups.javaClass.declaredMethods.single {
            it.name == "error" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val details: JSONObject.() -> Unit = {
            put("workspace_id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            put("can_replace", false)
            put("allowed_actions", org.json.JSONArray(listOf("get_task_result")))
            put("next_step", "wait for cleanup")
        }
        val result = method.invoke(AgentChildTaskGroups, "WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW", details)
            as AgentModelClient.ToolResult
        val json = JSONObject(result.content)
        assertFalse(json.getBoolean("ok"))
        assertEquals("WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW", json.getString("code"))
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", json.getString("workspace_id"))
        assertFalse(json.getBoolean("can_replace"))
        assertEquals("get_task_result", json.getJSONArray("allowed_actions").getString(0))
        assertEquals("wait for cleanup", json.getString("next_step"))
        assertTrue(json.getString("message").isNotBlank())
    }

    @Test fun archiveKeepsRecoveryMetadataButDoesNotAdvertiseContinuation() {
        val method = AgentChildTaskGroups.javaClass.declaredMethods.single { it.name == "archiveSnapshot" }
            .apply { isAccessible = true }
        val input = snapshot().put("allowed_actions", org.json.JSONArray(listOf("get_task_result")))
            .put("next_step", "inspect retained worktree").put("can_continue", true)
        val archived = JSONObject(method.invoke(AgentChildTaskGroups, input) as String)
        assertTrue(archived.getBoolean("archived"))
        assertFalse(archived.getBoolean("can_continue"))
        assertEquals("get_task_result", archived.getJSONArray("allowed_actions").getString(0))
        assertEquals("inspect retained worktree", archived.getString("next_step"))
    }

    @Test fun archivePreservesBoundedGitEvidenceSeparateFromModelClaims() {
        val method = AgentChildTaskGroups.javaClass.declaredMethods.single { it.name == "archiveSnapshot" }
            .apply { isAccessible = true }
        val evidence = JSONObject().put("base_commit", "a".repeat(40)).put("artifact_commit", "b".repeat(40))
            .put("changed_file_count", 2)
        val input = snapshot().put("status", "completed").put("role", "implementation")
            .put("delivery_state", "artifact_ready_pending_review").put("artifact_verified", true)
            .put("artifact_evidence", evidence).put("acceptance_verified", false)
            .put("model_report_unverified", "model-claim ".repeat(600))
        val archived = JSONObject(method.invoke(AgentChildTaskGroups, input) as String)
        assertEquals("artifact_ready_pending_review", archived.getString("delivery_state"))
        assertTrue(archived.getBoolean("artifact_verified"))
        assertFalse(archived.getBoolean("acceptance_verified"))
        assertEquals(evidence.toString(), archived.getJSONObject("artifact_evidence").toString())
        assertTrue(archived.getBoolean("model_report_unverified_truncated"))
        assertEquals(2048, archived.getString("model_report_unverified").length)
    }

    @Suppress("UNCHECKED_CAST")
    private fun groups() = AgentChildTaskGroups.javaClass.getDeclaredField("groups").apply { isAccessible = true }
        .get(AgentChildTaskGroups) as MutableMap<String, Any>
    private fun install(owner: String, generation: String, coordinator: SubAgentCoordinator, worker: AgentChildTaskGroups.Worker) {
        val type = AgentChildTaskGroups.javaClass.declaredClasses.single { it.simpleName == "Group" }
        val ctor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val group = ctor.newInstance(owner, "run-$generation", generation, "lease", coordinator, null, listOf(worker), null)
        type.getDeclaredField("leaseHeld").apply { isAccessible = true }.set(group, false)
        synchronized(AgentChildTaskGroups) { groups()[generation] = group }
    }
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())

    @Test fun registryInternalReplacementProbeCannotAuthorizeItselfAndClaimPreventsReplay() {
        val owner = UUID.randomUUID().toString()
        val oldGeneration = UUID.randomUUID().toString()
        val currentGeneration = UUID.randomUUID().toString()
        val oldWorker = worker(owner)
        val currentWorker = worker(owner, revision = "new")
        val old = SubAgentCoordinator(listOf(model), workerIds = listOf("stable")) { _, _, controller ->
            controller.reportTaskProgress("Checked source; test remains")
            throw IllegalStateException("fixture failure")
        }
        val next = SubAgentCoordinator(listOf(model.copy(model = "new")), workerIds = listOf("stable")) { config, _, _ ->
            assertEquals("new", config.model)
            "successor"
        }
        install(owner, oldGeneration, old, oldWorker)
        install(owner, currentGeneration, next, currentWorker)
        try {
            val id = JSONObject(old.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (old.hasActiveTasks() && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(old.hasActiveTasks())
            val args = JSONObject().put("task", "finish").put("replace_task_id", id)
            fun replace() = JSONObject(AgentChildTaskGroups.execute(owner, currentGeneration, call("delegate_task", args)).content)
            assertEquals("HANDOFF_NOT_READ", replace().getString("code"))
            assertEquals("HANDOFF_NOT_READ", replace().getString("code"))
            val read = JSONObject(AgentChildTaskGroups.execute(owner, currentGeneration,
                call("get_task_result", JSONObject().put("task_id", id))).content)
            assertTrue(read.getBoolean("execution_stopped"))
            assertTrue(read.has("handoff_version"))
            val successor = replace()
            assertTrue(successor.toString(), successor.getBoolean("ok"))
            assertEquals(id, successor.getString("replaces_task_id"))
            assertFalse(replace().getBoolean("ok"))
            assertEquals(1, next.taskIds().size)
        } finally {
            old.close(); next.close()
            synchronized(AgentChildTaskGroups) { groups().remove(oldGeneration); groups().remove(currentGeneration) }
            val forget = AgentChildTaskGroups.javaClass.declaredMethods.single { it.name == "forgetGeneration" }.apply { isAccessible = true }
            synchronized(AgentChildTaskGroups) { forget.invoke(AgentChildTaskGroups, oldGeneration); forget.invoke(AgentChildTaskGroups, currentGeneration) }
        }
    }
}
