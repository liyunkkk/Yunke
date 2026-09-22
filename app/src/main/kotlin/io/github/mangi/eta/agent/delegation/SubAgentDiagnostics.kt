package io.github.mangi.eta.agent.delegation

import java.security.MessageDigest
import org.json.JSONObject

/** Allowlisted metadata only. Never accepts prompt, arguments, result bodies, URLs or headers. */
internal class SubAgentDiagnostics(
    private val parentRunId: String = "",
    private val sink: (String) -> Unit = {},
) {
    fun mark(stage: String, taskId: String = "", agentId: String = "", providerId: String = "", model: String = "",
        role: String = "", status: String = "", errorCode: String = "", failure: Throwable? = null,
        metrics: Map<String, Number> = emptyMap(), toolName: String = "") {
        runCatching {
            val data=JSONObject().put("schema",1).put("run_ref",reference(parentRunId))
                .put("stage",token(stage)).put("task_id",if(taskId.matches(Regex("[a-f0-9-]{36}"))) taskId else reference(taskId))
                .put("agent_ref",reference(agentId)).put("provider_ref",reference(providerId)).put("model_ref",reference(model))
                .put("role",if(role in roles) role else "").put("status",if(status in statuses) status else "")
            if(errorCode.isNotBlank()) data.put("error_code", if(errorCode.matches(Regex("[A-Z][A-Z0-9_]{0,79}"))) errorCode else "UNCLASSIFIED")
            failure?.let { data.put("exception_type",token(it.javaClass.simpleName)); data.put("origin",it.stackTrace.firstOrNull { f -> f.className.startsWith("io.github.mangi.eta.") }?.let { f -> "${f.className}:${f.lineNumber}" }.orEmpty()) }
            failure?.message?.let { message ->
                Regex("\\bHTTP\\s+(\\d{3})\\b",RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 100..599 }?.let { data.put("http_status",it) }
            }
            if(toolName.isNotBlank()) data.put("tool",if(SubAgentTools.allows(toolName) || toolName == "workspace_file") toolName else reference(toolName))
            metrics.filterKeys { it in metricKeys }.forEach { (key,value) -> data.put(key,value) }
            sink("SubAgentDiag $data")
        }
    }
    companion object {
        private val roles=setOf("implementation","review","research","summary","image_generation","video_generation")
        private val statuses=setOf("queued","running","awaiting_decision","completed","cancelled","timed_out","failed")
        private val metricKeys=setOf("elapsed_ms","queue_ms","execution_ms","compression_ms","decision_wait_ms","limit","continuations","round","http_status","attempt","delay_ms","context_tokens","input_tokens","output_tokens","tool_ok","active","queued","workspace_present")
        private fun token(value:String)=value.take(80).replace(Regex("[^A-Za-z0-9_.-]"),"_")
        private fun reference(value:String):String = if(value.isEmpty()) "" else MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
    }
}
