package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, bounded request-shape diagnostics.
 *
 * Every returned value is a count, a fixed enum or an explicit estimate. Nothing here
 * retains or logs message text, tool arguments/results, ciphertext, endpoints or credentials, and
 * nothing mutates the arrays it is given. Opaque reasoning (`encrypted_content`) is reported only as
 * a character volume and is deliberately never converted to tokens.
 *
 * These helpers never influence budgeting, compaction or the wire body; callers only project the
 * result into the bounded [AgentToolCallDiagnostics] records.
 */
internal object AgentRequestContextDiagnostics {

    /** Shared across all transports; fixed labels and numeric aggregates only. */
    fun wireBodyFields(shape: AgentWireRequestEstimate.Shape): JSONObject = JSONObject().apply {
        put("body_basis", when (shape.endpoint) {
            EndpointKind.RESPONSES -> "final_responses_http"
            EndpointKind.CHAT_COMPLETIONS -> "final_chat_completions_http"
            EndpointKind.ANTHROPIC_MESSAGES -> "final_anthropic_http"
        })
        put("request_tokens_est", shape.tokens)
        for ((name, component) in listOf(
            "instructions" to shape.instructions, "text" to shape.text,
            "reasoning_text" to shape.reasoning, "tool_calls" to shape.toolCalls,
            "tool_results" to shape.toolResults, "tool_schema" to shape.tools,
            "format" to shape.format, "media" to shape.media,
        )) {
            put("${name}_chars", component.chars)
            put("${name}_utf8_bytes", component.utf8Bytes)
            put("${name}_tokens_est", component.tokens)
        }
        put("framing_tokens_est", shape.framingTokens)
        put("message_count", shape.messageCount)
        put("tool_count", shape.toolCount)
        put("hosted_tool_count", shape.hostedToolCount)
        put("image_count", shape.imageCount)
        put("video_count", shape.videoCount)
        put("opaque_replay_items", shape.opaqueItems)
        put("encrypted_content_chars", shape.encryptedChars)
        put("opaque_replay_priced", false)
        put("hosted_internal_priced", false)
        put("unknown_content_blocks", shape.unknownBlocks)
    }

    /**
     * Shape of the *final* Responses HTTP body, paired with the messages it was built from so the
     * coverage gap between the raw history and the projected input stays attributable.
     */
    data class ResponseBody(
        val sourceMessages: Int,
        val ephemeralMessages: Int,
        val ephemeralItems: Int,
        val inputItems: Int,
        val inputMessageItems: Int,
        val inputFunctionCallItems: Int,
        val inputFunctionCallOutputItems: Int,
        val inputReasoningItems: Int,
        val inputOtherItems: Int,
        val encryptedContentChars: Long,
        val instructionsChars: Int,
        val instructionsTokens: Int,
        val toolCount: Int,
        val toolSchemaTokens: Int,
    )

    fun responseBody(request: JSONObject, messages: JSONArray): ResponseBody {
        var messageItems = 0
        var functionCallItems = 0
        var functionCallOutputItems = 0
        var reasoningItems = 0
        var otherItems = 0
        var encryptedChars = 0L
        val input = request.optJSONArray("input")
        if (input != null) {
            for (index in 0 until input.length()) {
                val item = input.optJSONObject(index) ?: continue
                // Opaque ciphertext volume only. Never fed to a tokenizer or the budget.
                encryptedChars += item.optString("encrypted_content").length.toLong()
                when (item.optString("type")) {
                    "message" -> messageItems++
                    "function_call" -> functionCallItems++
                    "function_call_output" -> functionCallOutputItems++
                    "reasoning" -> reasoningItems++
                    else -> otherItems++
                }
            }
        }
        var ephemeralMessages = 0
        var ephemeralItems = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val items = ResponsesEphemeralState.outputItems(message) ?: continue
            if (items.length() > 0) {
                ephemeralMessages++
                ephemeralItems += items.length()
            }
        }
        val instructions = request.optString("instructions")
        val tools = request.optJSONArray("tools")
        return ResponseBody(
            sourceMessages = messages.length(),
            ephemeralMessages = ephemeralMessages,
            ephemeralItems = ephemeralItems,
            inputItems = input?.length() ?: 0,
            inputMessageItems = messageItems,
            inputFunctionCallItems = functionCallItems,
            inputFunctionCallOutputItems = functionCallOutputItems,
            inputReasoningItems = reasoningItems,
            inputOtherItems = otherItems,
            encryptedContentChars = encryptedChars,
            instructionsChars = instructions.length,
            instructionsTokens = AgentContextBudget.countTokens(instructions),
            toolCount = tools?.length() ?: 0,
            toolSchemaTokens = if (tools == null) 0 else AgentRequestTokenEstimate.tools(tools),
        )
    }

    fun responseBodyFields(body: ResponseBody): JSONObject = JSONObject()
        .put("body_basis", "final_responses_http")
        .put("source_messages", body.sourceMessages)
        .put("ephemeral_messages", body.ephemeralMessages)
        .put("ephemeral_items", body.ephemeralItems)
        .put("input_items", body.inputItems)
        .put("input_message_items", body.inputMessageItems)
        .put("input_function_call_items", body.inputFunctionCallItems)
        .put("input_function_call_output_items", body.inputFunctionCallOutputItems)
        .put("input_reasoning_items", body.inputReasoningItems)
        .put("input_other_items", body.inputOtherItems)
        .put("encrypted_content_chars", body.encryptedContentChars)
        .put("instructions_chars", body.instructionsChars)
        .put("instructions_tokens_est", body.instructionsTokens)
        .put("tool_count", body.toolCount)
        .put("tool_schema_tokens_est", body.toolSchemaTokens)

    /**
     * Local, pre-request estimate snapshot for one request round. Estimates carry `_est` and a
     * basis enum so they can never be mistaken for a cloud measurement.
     */
    data class LocalRequest(
        val sourceMessages: Int,
        val systemCount: Int,
        val boundaryTokens: Int,
        val rawHistoryTokens: Int,
        val fixedTokens: Int,
        val filteredTokens: Int?,
        val filteredBasis: String,
        val cloudInput: Int? = null,
        val cloudCached: Int? = null,
        val cloudCacheCreation: Int? = null,
        val cloudOutput: Int? = null,
        val opaqueReplayItems: Int = 0,
        val opaqueEncryptedChars: Long = 0,
        val requestBodyTokens: Int? = null,
    )

    fun localRequestFields(local: LocalRequest): JSONObject = JSONObject()
        .put("source_messages", local.sourceMessages)
        .put("system_count", local.systemCount)
        .put("boundary_tokens_est", local.boundaryTokens)
        .put("raw_history_tokens_est", local.rawHistoryTokens)
        .put("fixed_tokens_est", local.fixedTokens)
        .put("filtered_basis", local.filteredBasis)
        .apply {
            local.filteredTokens?.let { put("filtered_tokens_est", it) }
            local.requestBodyTokens?.let { put("request_tokens_est", it) }
            local.cloudInput?.let { put("cloud_input", it) }
            local.cloudCached?.let { put("cloud_cached", it) }
            local.cloudCacheCreation?.let { put("cloud_cache_creation", it) }
            local.cloudOutput?.let { put("cloud_output", it) }
            if (local.opaqueReplayItems > 0) {
                put("opaque_replay_items", local.opaqueReplayItems)
                put("opaque_encrypted_content_chars", local.opaqueEncryptedChars)
                put("opaque_replay_priced", false)
                put("opaque_input_basis", "unknown_unpriced_replay")
            }
        }

    /** Counts replayed reasoning items. Ciphertext length is reported separately and is not a token price. */
    fun replayedOpaque(messages: JSONArray, config: AgentModelClient.ModelConfig): Pair<Int, Long> {
        if (config.openAiEndpointMode != io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES) return 0 to 0L
        var items = 0
        var chars = 0L
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val replayed = replayedReasoning(message, config) ?: continue
            for (itemIndex in 0 until replayed.length()) {
                val item = replayed.optJSONObject(itemIndex) ?: continue
                if (item.optString("type") != "reasoning") continue
                items++
                chars += item.optString("encrypted_content").length.toLong()
            }
        }
        return items to chars
    }

    private fun replayedReasoning(message: JSONObject, config: AgentModelClient.ModelConfig): JSONArray? {
        val ephemeral = ResponsesEphemeralState.outputItems(message)
        if (ephemeral != null && ephemeral.length() > 0) {
            val reasoning = JSONArray()
            for (index in 0 until ephemeral.length()) {
                val item = ephemeral.optJSONObject(index) ?: continue
                if (item.optString("type") == "reasoning") reasoning.put(item)
            }
            return reasoning.takeIf { it.length() > 0 }
        }
        return ResponsesReasoningState.items(message, config)
    }
}
