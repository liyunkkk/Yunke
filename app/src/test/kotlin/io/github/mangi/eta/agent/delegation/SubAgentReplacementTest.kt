package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Replacement requires exact provenance, an isolated predecessor and another provider. Not run locally. */
class SubAgentReplacementTest {
    private fun call(c: SubAgentCoordinator, name: String, args: JSONObject) = JSONObject(
        c.execute(AgentModelClient.ToolCall("id", name, args.toString())).content)
    private val first = AgentModelClient.ModelConfig(baseUrl = "https://first.invalid", apiKey = "secret-one", providerId = "provider-one", model = "model-one", systemPrompt = "")
    private val second = first.copy(baseUrl = "https://second.invalid", apiKey = "secret-two", providerId = "provider-two", model = "model-two")

    @Test fun failedProviderIsNotSilentlyReplacedAndSameProviderIsRejected() {
        SubAgentCoordinator(listOf(first, first.copy(model = "same-provider"), second), executeChild = { config, _, _ ->
            if (config.providerId == "provider-one") throw AgentModelFailure("HTTP_503", true, "HTTP 503")
            "fresh evidence"
        }).use { c ->
            val old = call(c, "delegate_task", JSONObject().put("task", "inspect").put("worker", 1)).getString("task_id")
            val failed = call(c, "get_task_result", JSONObject().put("task_id", old).put("wait_ms", 1000))
            assertEquals("failed", failed.getString("status"))
            assertTrue(failed.getBoolean("can_replace"))
            assertEquals("model-one", failed.getString("model"))
            assertFalse(failed.toString().contains("secret-one"))
            val args = JSONObject().put("task", "recheck only safe reads").put("replace_task_id", old)
            assertEquals("REPLACEMENT_PROVIDER_UNAVAILABLE", call(c, "delegate_task", JSONObject(args.toString()).put("worker", 2)).getString("code"))
            val newer = call(c, "delegate_task", JSONObject(args.toString()).put("worker", 3))
            assertEquals("completed", call(c, "get_task_result", JSONObject().put("task_id", newer.getString("task_id")).put("wait_ms", 1000)).getString("status"))
            assertEquals("failed", call(c, "get_task_result", JSONObject().put("task_id", old)).getString("status"))
        }
    }

    @Test fun healthyTaskCannotBeReplacedEvenWithDifferentWorker() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(first, second), executeChild = { _, _, _ ->
            started.countDown(); release.await(); "healthy"
        }).use { c ->
            val old = call(c, "delegate_task", JSONObject().put("task", "original").put("worker", 1)).getString("task_id")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            try {
                assertFalse(call(c, "get_task_result", JSONObject().put("task_id", old)).getBoolean("can_replace"))
                assertEquals("REPLACEMENT_NOT_ALLOWED", call(c, "delegate_task", JSONObject().put("task", "duplicate")
                    .put("worker", 2).put("replace_task_id", old)).getString("code"))
            } finally { release.countDown() }
        }
    }
}
