package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Runtime 事件的稳定 JSON 投影；只编码 IPC 已公开的安全字段。 */
internal object AgentEventJsonCodec {
    // 单条事件持久化上限（字节）：SQLite CursorWindow 单窗口约 2MB，取 512KB 留约 4 倍边界。
    internal const val MAX_PERSISTED_EVENT_BYTES = 512 * 1024
    // 读取前自愈清理阈值：旧版本写入的超限行在加载前删除，避免读取时 CursorWindow 溢出。
    internal const val MAX_READABLE_EVENT_BYTES = 1024 * 1024
    // 未知字段膨胀时的最小占位；读取端对未知 type 返回 null，会被跳过。
    private const val OVERSIZED_PLACEHOLDER_JSON = "{\"type\":\"oversized_dropped\"}"

    fun encode(event: AgentEvent): String {
        val json = bundleToJson(AgentRuntimeWire.eventToBundle(event)).toString()
        if (json.toByteArray(Charsets.UTF_8).size <= MAX_PERSISTED_EVENT_BYTES) return json
        // 已知膨胀源：context_compacted 在 historyDescriptor == null 时内联全量压缩历史。
        // 降级为同型但去掉 history 的事件：applied/计数/原因保留，恢复重放按「无历史」处理。
        if (event is AgentEvent.ContextCompacted && event.history.isNotEmpty()) {
            val downgraded = bundleToJson(
                AgentRuntimeWire.eventToBundle(event.copy(history = emptyList()))
            ).toString()
            if (downgraded.toByteArray(Charsets.UTF_8).size <= MAX_PERSISTED_EVENT_BYTES) return downgraded
        }
        // 兜底：未知字段膨胀时存最小占位，读取端解码为 null 并被跳过。
        return OVERSIZED_PLACEHOLDER_JSON
    }

    fun decode(raw: String): AgentEvent? = runCatching {
        AgentRuntimeWire.eventFromBundle(jsonToBundle(JSONObject(raw)))
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): JSONObject =
        JSONObject().also { json ->
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is ArrayList<*> -> json.put(key, JSONArray(value))
                    null -> json.put(key, JSONObject.NULL)
                }
            }
        }

    private fun jsonToBundle(json: JSONObject): Bundle =
        Bundle().also { bundle ->
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is JSONArray -> bundle.putStringArrayList(
                        key,
                        ArrayList((0 until value.length()).map { index -> value.optString(index) }),
                    )
                }
            }
        }
}
