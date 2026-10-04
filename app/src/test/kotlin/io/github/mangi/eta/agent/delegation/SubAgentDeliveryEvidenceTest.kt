package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubAgentDeliveryEvidenceTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())
    private fun code(json: JSONObject): String? = try {
        SubAgentDeliveryEvidence.verify(json, SubAgentDeliveryFixture.ID, SubAgentDeliveryFixture.BASE)
        null
    } catch (error: WorkspaceOperationException) { error.code }

    @Test fun nonemptyReceiptIsCandidateEvidenceNotRequirementsAcceptance() {
        val verified = SubAgentDeliveryEvidence.verify(SubAgentDeliveryFixture.ready(), SubAgentDeliveryFixture.ID, SubAgentDeliveryFixture.BASE)
        assertEquals(1L, verified.getLong("changed_file_count"))
        assertFalse(verified.has("acceptance_verified"))
        assertEquals(SubAgentDeliveryFixture.COMMIT, verified.getString("artifact_commit"))
    }
    @Test fun missingStaleDirtyForeignOrLooselyTypedReceiptsFailClosed() {
        val variants: List<(JSONObject) -> Unit> = listOf(
            { it.remove("artifact_evidence") },
            { it.put("base", "HEAD") },
            { it.put("state", "editing") },
            { it.put("id", "f".repeat(32)) },
            { it.getJSONObject("artifact_evidence").put("source", "model") },
            { it.getJSONObject("artifact_evidence").put("head_matches_commit", false) },
            { it.getJSONObject("artifact_evidence").put("clean_worktree", false) },
            { it.getJSONObject("artifact_evidence").put("base_is_ancestor", false) },
            { it.getJSONObject("artifact_evidence").put("net_diff_verified", "true") },
            { it.getJSONObject("artifact_evidence").put("artifact_commit", "c".repeat(40)) },
            { it.getJSONObject("artifact_evidence").put("changed_file_count", "1") },
            { it.getJSONObject("artifact_evidence").put("changed_file_count", 1.5) },
            { it.getJSONObject("artifact_evidence").put("changed_file_count", -1) },
            { it.getJSONObject("artifact_evidence").put("changed_file_count", Long.MAX_VALUE) }
        )
        for (mutate in variants) assertEquals(SubAgentDeliveryEvidence.INVALID, code(SubAgentDeliveryFixture.ready().also(mutate)))
        val empty = SubAgentDeliveryFixture.ready()
        empty.getJSONObject("artifact_evidence").put("changed_file_count", 0)
        assertEquals(SubAgentDeliveryEvidence.EMPTY, code(empty))
    }
    @Test fun successRequiresFreshInspectAndMatchingCommit() {
        val operations = mutableListOf<String>()
        val backend = SubAgentWorkspace("fixture", AgentModelClient.ToolExecutor { call ->
            SubAgentDeliveryFixture.response(call) { action, result ->
                operations += action
                if (action == "inspect") {
                    result.put("commit", "c".repeat(40))
                    result.getJSONObject("artifact_evidence").put("artifact_commit", "c".repeat(40))
                }
            }
        })
        try {
            backend.sealImplementation("/workspace/project", SubAgentDeliveryFixture.ID, SubAgentDeliveryFixture.BASE, AgentRunController())
            fail("stale seal receipt must not pass")
        } catch (error: WorkspaceOperationException) { assertEquals(SubAgentDeliveryEvidence.INVALID, error.code) }
        assertEquals(listOf("seal", "inspect"), operations)
    }
    private fun awaitFinal(c: SubAgentCoordinator, id: String): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val json = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id))).content)
            if (json.optBoolean("execution_exited") && json.optString("status") !in setOf("running", "queued")) return json
            Thread.sleep(5)
        }
        error("fixture task did not stop")
    }
    private fun start(c: SubAgentCoordinator): String = JSONObject(c.execute(call("delegate_task", JSONObject()
        .put("task", "wire production entry point").put("role", "implementation").put("project", "/workspace/project"))).content).getString("task_id")

    @Test fun modelClaimCannotTurnEmptyOrMissingArtifactIntoCompleted() {
        for (failure in listOf(SubAgentDeliveryEvidence.EMPTY, SubAgentDeliveryEvidence.INVALID)) {
            val backend = SubAgentWorkspace("fixture", AgentModelClient.ToolExecutor { call ->
                SubAgentDeliveryFixture.response(call) { action, result ->
                    if (action == "seal") {
                        if (failure == SubAgentDeliveryEvidence.EMPTY) result.put("ok", false).put("code", failure)
                        else result.remove("artifact_evidence")
                    }
                }
            })
            SubAgentCoordinator(listOf(model), roles = listOf("implementation"), workspace = backend,
                executeWorkspaceChild = { _, _, _, _, _, _ -> "Everything wired; all tests passed!" },
                executeChild = { _, _, _ -> error("wrong runner") }).use { c ->
                val result = awaitFinal(c, start(c))
                assertEquals("failed", result.getString("status"))
                assertEquals(failure, result.getString("error_code"))
                assertFalse(result.getBoolean("artifact_verified"))
                assertFalse(result.getBoolean("acceptance_verified"))
                assertFalse(result.getBoolean("can_replace"))
                assertFalse(result.getString("result").contains("all tests passed"))
                assertEquals("Everything wired; all tests passed!", result.getString("model_report_unverified"))
            }
        }
    }
    @Test fun verifiedCommitCompletesOnlyAsPendingIndependentReview() {
        val backend = SubAgentWorkspace("fixture", AgentModelClient.ToolExecutor { SubAgentDeliveryFixture.response(it) })
        SubAgentCoordinator(listOf(model), roles = listOf("implementation"), workspace = backend,
            executeWorkspaceChild = { _, _, _, _, _, _ -> "edited" },
            executeChild = { _, _, _ -> error("wrong runner") }).use { c ->
            val result = awaitFinal(c, start(c))
            assertEquals("completed", result.getString("status"))
            assertEquals("artifact_ready_pending_review", result.getString("delivery_state"))
            assertTrue(result.getBoolean("artifact_verified"))
            assertFalse(result.getBoolean("acceptance_verified"))
            assertEquals("edited", result.getString("model_report_unverified"))
            assertEquals(SubAgentDeliveryFixture.COMMIT, result.getJSONObject("artifact_evidence").getString("artifact_commit"))
        }
    }
    @Test fun cancelledInspectCannotPublishLateArtifactOrReviveTask() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = SubAgentWorkspace("fixture", AgentModelClient.ToolExecutor { call ->
            SubAgentDeliveryFixture.response(call) { action, _ ->
                if (action == "inspect") {
                    entered.countDown()
                    try { release.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* simulate late response */ }
                }
            }
        })
        SubAgentCoordinator(listOf(model), roles = listOf("implementation"), workspace = backend,
            executeWorkspaceChild = { _, _, _, _, _, _ -> "edited" },
            executeChild = { _, _, _ -> error("wrong runner") }).use { c ->
            try {
                val id = start(c)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                c.execute(call("cancel_task", JSONObject().put("task_id", id)))
                release.countDown()
                val result = awaitFinal(c, id)
                assertEquals("cancelled", result.getString("status"))
                assertFalse(result.getBoolean("artifact_verified"))
                assertFalse(result.getBoolean("acceptance_verified"))
                assertTrue(result.isNull("artifact_evidence"))
            } finally { release.countDown() }
        }
    }
}
