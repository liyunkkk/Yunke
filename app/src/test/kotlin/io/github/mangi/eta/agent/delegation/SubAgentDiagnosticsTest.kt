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
            assertTrue("worker_released diagnostic was not delivered", diagnosticsDelivered.await(5, TimeUnit.SECONDS))
            val captured = synchronized(lines) { lines.toList() }
            val error=captured.map{JSONObject(it.removePrefix("SubAgentDiag "))}.single{it.getString("stage")=="worker_exception"}
            assertEquals(503,error.getInt("http_status"));assertEquals("IllegalStateException",error.getString("exception_type"))
            assertFalse(captured.joinToString().contains("private-provider-body"))
        }
    }

}
