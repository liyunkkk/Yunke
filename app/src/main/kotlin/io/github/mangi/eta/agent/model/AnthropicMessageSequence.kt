package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages 的 tool_result 配对规范化。
 *
 * 官方要求：同一批 tool_use 的全部 tool_result 必须出现在紧随该 assistant 的同一条 user 消息里，
 * 并且 tool_result 块排在这条消息的任何 text/image 之前。代理内部历史用的是 OpenAI 形状
 * （每个工具结果一条 role=tool），逐条映射会产生多条连续 user，服务端按 Invalid message sequence 拒绝。
 *
 * 规范化只做合并与块排序：不补造结果、不丢弃调用、不跨 assistant 搬移结果。配对本身不成立
 * （缺失、孤立、重复、空 id）时在本地明确失败，交由上层处理。
 */
internal object AnthropicMessageSequence {
    private const val FAILURE_CODE = "ANTHROPIC_TOOL_PAIRING_INVALID"
    private const val TOOL_RESULT = "tool_result"
    private const val TOOL_USE = "tool_use"

    /** 合并相邻同 role 消息，并把每批 tool_result 提到该 user 消息前部。 */
    fun normalize(messages: JSONArray): JSONArray {
        val merged = mergeAdjacentSameRole(messages)
        validate(merged, origin = "provider")
        return merged
    }

    /**
     * 自定义请求体合并之后的最终校验。
     *
     * 这里只校验配对不变量，不改写用户刻意指定的 messages，避免自定义语义被规范化掉，
     * 同时保证 customBody 覆盖 messages 时不会绕过配对检查。
     */
    fun validateFinalRequest(request: JSONObject) {
        val messages = request.optJSONArray("messages") ?: return
        validate(messages, origin = "final_request")
    }

    private fun mergeAdjacentSameRole(messages: JSONArray): JSONArray {
        val out = JSONArray()
        var currentRole: String? = null
        var currentBlocks: MutableList<JSONObject>? = null

        fun flush() {
            val role = currentRole
            val blocks = currentBlocks
            if (role != null && blocks != null) {
                out.put(JSONObject().put("role", role).put("content", orderBlocks(role, blocks)))
            }
            currentRole = null
            currentBlocks = null
        }

        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                // 非块数组内容（provider 不会产生）按原样保留，不参与合并。
                flush()
                out.put(message)
                continue
            }
            val blocks = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            val pending = currentBlocks
            if (role == currentRole && pending != null) {
                pending.addAll(blocks)
            } else {
                flush()
                currentRole = role
                currentBlocks = blocks.toMutableList()
            }
        }
        flush()
        return out
    }

    /** user 消息内：tool_result 保持原相对顺序排在前，其余（text/image）保持原相对顺序排在后。 */
    private fun orderBlocks(role: String, blocks: List<JSONObject>): JSONArray {
        if (role != "user") return JSONArray().also { array -> blocks.forEach(array::put) }
        val results = blocks.filter { it.optString("type") == TOOL_RESULT }
        val others = blocks.filterNot { it.optString("type") == TOOL_RESULT }
        // 合并可能带入空文本占位块；只有在没有任何其它块时才保留它，以保证 content 非空。
        val keptOthers = others.filterNot { isBlankText(it) }
        val tail = if (results.isEmpty() && keptOthers.isEmpty()) others else keptOthers
        return JSONArray().also { array ->
            results.forEach(array::put)
            tail.forEach(array::put)
        }
    }

    private fun isBlankText(block: JSONObject): Boolean =
        block.optString("type") == "text" && block.optString("text").isBlank()

    private fun validate(messages: JSONArray, origin: String) {
        var pendingIds: List<String>? = null
        var pendingIndex = -1

        scan@ for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue@scan
            val blocks = blocksOf(message)
            when (message.optString("role")) {
                "assistant" -> {
                    pendingIds?.let { ids ->
                        fail(origin, "tool_use 之后未出现 tool_result；calls=${ids.size}; assistant_index=$pendingIndex")
                    }
                    val ids = blocks.filter { it.optString("type") == TOOL_USE }.map { it.optString("id") }
                    if (ids.isEmpty()) continue@scan
                    if (ids.any { it.isBlank() }) {
                        fail(origin, "assistant 存在空 tool_use id；calls=${ids.size}; message_index=$index")
                    }
                    val duplicateCalls = ids.groupingBy { it }.eachCount().count { it.value > 1 }
                    if (duplicateCalls > 0) {
                        fail(origin, "assistant 存在重复 tool_use id；duplicate_ids=$duplicateCalls; message_index=$index")
                    }
                    pendingIds = ids
                    pendingIndex = index
                }
                "user" -> {
                    val results = blocks.filter { it.optString("type") == TOOL_RESULT }
                    if (results.isEmpty()) {
                        pendingIds?.let { ids ->
                            fail(
                                origin,
                                "tool_use 之后的 user 消息没有 tool_result；calls=${ids.size}; " +
                                    "assistant_index=$pendingIndex; message_index=$index",
                            )
                        }
                        continue@scan
                    }
                    val expected = pendingIds ?: fail(
                        origin,
                        "孤立 tool_result：前面没有待配对的 tool_use；results=${results.size}; message_index=$index",
                    )
                    val resultIds = results.map { it.optString("tool_use_id") }
                    if (resultIds.any { it.isBlank() }) {
                        fail(origin, "tool_result 存在空 tool_use_id；results=${resultIds.size}; message_index=$index")
                    }
                    val duplicateResults = resultIds.groupingBy { it }.eachCount().count { it.value > 1 }
                    if (duplicateResults > 0) {
                        fail(origin, "同批出现重复 tool_result；duplicate_ids=$duplicateResults; message_index=$index")
                    }
                    val missing = expected.toSet() - resultIds.toSet()
                    val unknown = resultIds.toSet() - expected.toSet()
                    if (missing.isNotEmpty() || unknown.isNotEmpty()) {
                        fail(
                            origin,
                            "tool_result 与 tool_use 不匹配；calls=${expected.size}; results=${resultIds.size}; " +
                                "missing=${missing.size}; unknown=${unknown.size}; " +
                                "assistant_index=$pendingIndex; message_index=$index",
                        )
                    }
                    val lastResult = blocks.indexOfLast { it.optString("type") == TOOL_RESULT }
                    val firstOther = blocks.indexOfFirst { it.optString("type") != TOOL_RESULT }
                    if (firstOther in 0 until lastResult) {
                        fail(origin, "tool_result 未排在 text/image 之前；blocks=${blocks.size}; message_index=$index")
                    }
                    pendingIds = null
                    pendingIndex = -1
                }
            }
        }

        pendingIds?.let { ids ->
            fail(origin, "末尾 tool_use 没有对应 tool_result；calls=${ids.size}; assistant_index=$pendingIndex")
        }
    }

    private fun blocksOf(message: JSONObject): List<JSONObject> {
        val content = message.optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }
    }

    private fun fail(origin: String, detail: String): Nothing = throw AgentModelFailure(
        code = FAILURE_CODE,
        retryable = false,
        message = "Anthropic 工具结果配对不完整，已在本地拦截请求：$detail",
        // 仅统计信息与位置，不含消息正文、工具输出或密钥。
        diagnostic = "anthropic_tool_pairing origin=$origin; $detail",
    )
}
