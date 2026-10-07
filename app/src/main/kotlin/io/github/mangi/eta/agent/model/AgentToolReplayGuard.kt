package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** IDs are evidence, not an instruction to redo an already accepted operation. */
internal class AgentToolReplayGuard(history: JSONArray) {
    private val knownIds = mutableSetOf<String>()
    private val dispatchedIds = mutableSetOf<String>()

    init {
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            remember(message)
            if (message.optString("role") == "tool") {
                message.optString("tool_call_id").takeIf { it.isNotBlank() }?.let(knownIds::add)
            }
        }
    }

    fun validate(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.TOOL_USE) return
        val message = response.assistantMessage
        val calls = AgentConversationCodec.parseToolCalls(message)
        val raw = message.optJSONArray("tool_calls")
        val batchIds = mutableSetOf<String>()
        val repeatsRaw = raw != null && (0 until raw.length()).any { index ->
            val id = raw.optJSONObject(index)?.optString("id").orEmpty().ifBlank { "tool_call_$index" }
            id in knownIds || !batchIds.add(id)
        }
        if (repeatsRaw || calls.any { it.id in knownIds }) throw AgentModelFailure(
            CODE, false,
            "模型返回了历史工具调用标识，本批未执行；保留原结果并按重连时限重新规划，不重复已完成或结果不明的操作。",
        )
    }

    fun remember(message: JSONObject) {
        val raw = message.optJSONArray("tool_calls")
        if (raw != null) for (index in 0 until raw.length()) {
            raw.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(knownIds::add)
        }
        AgentConversationCodec.parseToolCalls(message).forEach { knownIds += it.id }
    }

    @Synchronized
    fun claimDispatch(id: String): Boolean = dispatchedIds.add(id)

    companion object { const val CODE = "TOOL_CALL_REPLAY_BLOCKED" }
}
