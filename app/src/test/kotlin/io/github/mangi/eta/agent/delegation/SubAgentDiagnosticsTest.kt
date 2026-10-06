package io.github.mangi.eta.agent.delegation

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubAgentDiagnosticsTest {
    @Test fun writesOnlyAllowlistedMetadataAndHashesUserControlledIdentity() {
        val lines=mutableListOf<String>()
        val d=SubAgentDiagnostics("private-run",lines::add)
        d.mark("provider_response",taskId="12345678-1234-1234-1234-123456789012",agentId="private-agent",providerId="private-provider",model="sk-secret-model",
            role="research",status="failed",errorCode="SUB_AGENT_FAILED",failure=IllegalStateException("Bearer private-key body prompt text"),
            metrics=mapOf("http_status" to 429,"prompt" to 123),toolName="private-tool")
        val text=lines.single()
        for(secret in listOf("private-run","private-agent","private-provider","sk-secret-model","private-key","body prompt text","private-tool","\"prompt\"")) assertFalse(text.contains(secret))
        val j=JSONObject(text.removePrefix("SubAgentDiag "))
        assertEquals(429,j.getInt("http_status"));assertEquals("IllegalStateException",j.getString("exception_type"))
        assertEquals("12345678-1234-1234-1234-123456789012",j.getString("task_id"))
        assertEquals(16,j.getString("run_ref").length)
    }
    @Test fun failedLogSinkNeverChangesTaskOutcome() {
        SubAgentDiagnostics("r"){error("disk unavailable")}.mark("queued")
    }
    @Test fun malformedErrorsAreNotPersistedVerbatim() {
        val lines=mutableListOf<String>();val d=SubAgentDiagnostics("r",lines::add)
        d.mark("failed",errorCode="https://secret.invalid/?key=123",role="private-role",status="private-status")
        assertTrue(lines.single().contains("UNCLASSIFIED"));assertFalse(lines.single().contains("secret.invalid"))
    }
    @Test fun metadataLengthStaysBoundedAndNonPrimitiveMetricsCannotInjectText() {
        val lines = mutableListOf<String>()
        val failure = IllegalStateException("HTTP 503 private-provider-body").apply {
            stackTrace = arrayOf(StackTraceElement("io.github.mangi.eta." + "X".repeat(10_000), "private-method", "private-file", 123))
        }
        SubAgentDiagnostics("private-run", lines::add).mark("x".repeat(10_000),
            taskId = "private-task".repeat(1_000), agentId = "private-agent".repeat(1_000),
            providerId = "private-provider".repeat(1_000), model = "private-model".repeat(1_000),
            toolName = "private-tool".repeat(1_000), failure = failure,
            metrics = mapOf("context_tokens" to java.math.BigInteger("9".repeat(10_000)),
                "elapsed_ms" to Double.POSITIVE_INFINITY, "round" to 4L))
        val line = lines.single()
        val json = JSONObject(line.removePrefix("SubAgentDiag "))
        assertEquals(80, json.getString("stage").length)
        assertTrue(json.getString("origin").length <= 172)
        assertFalse(json.has("context_tokens"))
        assertFalse(json.has("elapsed_ms"))
        assertEquals(4L, json.getLong("round"))
        assertEquals(503, json.getInt("http_status"))
        assertTrue("Diagnostic metadata must stay bounded", line.length < 2_048)
        for (secret in listOf("private-run", "private-task", "private-agent", "private-provider", "private-model", "private-tool", "private-provider-body", "private-file", "private-method")) {
            assertFalse(line.contains(secret))
        }
    }

    @Test fun coordinatorLifecycleIsCorrelatedWithoutBodyLogging() {
        val lines=java.util.Collections.synchronizedList(mutableListOf<String>())
        val diagnosticsDelivered = CountDownLatch(1)
        val diagnostics = SubAgentDiagnostics("run") { line ->
            lines.add(line)
            if (JSONObject(line.removePrefix("SubAgentDiag ")).getString("stage") == "worker_released") {
                diagnosticsDelivered.countDown()
            }
        }
        val model=io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(baseUrl="https://secret.invalid",apiKey="private-key",model="private-model",systemPrompt="private-system")
        SubAgentCoordinator(listOf(model),diagnostics=diagnostics) {_,_,_-> "private-result" }.use { c ->
            val submitted=JSONObject(c.execute(io.github.mangi.eta.agent.model.AgentModelClient.ToolCall("id","delegate_task","""{"task":"private-prompt"}""")).content)
            val id=submitted.getString("task_id")
            val done=JSONObject(c.execute(io.github.mangi.eta.agent.model.AgentModelClient.ToolCall("id","get_task_result",JSONObject().put("task_id",id).put("wait_ms",3000).toString())).content)
            assertEquals("completed",done.getString("status"))
            assertTrue("worker_released diagnostic was not delivered", diagnosticsDelivered.await(5, TimeUnit.SECONDS))
            val captured = synchronized(lines) { lines.toList() }
            val all=captured.joinToString("\n")
            for(secret in listOf("private-key","private-model","private-system","private-prompt","private-result","secret.invalid")) assertFalse(all.contains(secret))
            val stages=captured.map{JSONObject(it.removePrefix("SubAgentDiag ")).getString("stage")}
            assertTrue(stages.indexOf("queued") < stages.indexOf("started"))
            assertTrue(stages.contains("completed"));assertTrue(all.contains(id))
        }
    }

    @Test fun workerFailureIncludesExceptionAndHttpButNeverProviderBody() {
        val lines=java.util.Collections.synchronizedList(mutableListOf<String>())
        val diagnosticsDelivered = CountDownLatch(1)
        val diagnostics = SubAgentDiagnostics("run") { line ->
            lines.add(line)
            if (JSONObject(line.removePrefix("SubAgentDiag ")).getString("stage") == "worker_released") {
                diagnosticsDelivered.countDown()
            }
        }
        val model=io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="test",model="test",systemPrompt="")
        SubAgentCoordinator(listOf(model),diagnostics=diagnostics) {_,_,_-> error("HTTP 503 private-provider-body") }.use { c ->
            val task=JSONObject(c.execute(io.github.mangi.eta.agent.model.AgentModelClient.ToolCall("id","delegate_task","""{"task":"work"}""")).content).getString("task_id")
            val done=JSONObject(c.execute(io.github.mangi.eta.agent.model.AgentModelClient.ToolCall("id","get_task_result",JSONObject().put("task_id",task).put("wait_ms",3000).toString())).content)
            assertEquals("failed",done.getString("status"));assertEquals("SUB_AGENT_FAILED",done.getString("error_code"))
            // The failure text names the class but never echoes the provider body.
            assertTrue(done.getString("result").contains("IllegalStateException"))
            assertFalse(done.getString("result").contains("private-provider-body"))
            assertTrue("worker_released diagnostic was not delivered", diagnosticsDelivered.await(5, TimeUnit.SECONDS))
            val captured = synchronized(lines) { lines.toList() }
            val error=captured.map{JSONObject(it.removePrefix("SubAgentDiag "))}.single{it.getString("stage")=="worker_exception"}
            assertEquals(503,error.getInt("http_status"));assertEquals("IllegalStateException",error.getString("exception_type"))
            assertFalse(captured.joinToString().contains("private-provider-body"))
        }
    }

}
