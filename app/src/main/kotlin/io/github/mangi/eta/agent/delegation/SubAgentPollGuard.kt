package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轮询退避门禁：只约束主代理对**仍在 running** 的子任务反复查询。
 *
 * 设计要点（与既有 `AgentDelegationArgumentRepair` 同形状，但不复制其永久失效语义）：
 * - `reject()` 只拒绝，不执行工具，也不伪造结果；拒绝时返回明确的错误码与建议等待毫秒。
 * - **终态永久放行**：只有上一次观测到的状态是 `running`（含排队）才计数，避免阻断结果获取。
 * - 不带 `task_id` 的列表查询、`cancel_task` / `continue_task` 完全不受影响。
 * - `suspended` 语义是**有时限的挂起**（[REMOVE_FOR_MS]）：摘除工具只是让主代理冷静一下，
 *   到点自动恢复，绝不会永久挡住结果取回。
 * - **不做长阻塞**：不改变 `wait_ms ≤ 10000` 的既有上限。手机端不能让用户等待十几分钟。
 * - 首档间隔按 `get_task_result` 的现有等待上限取 20 秒，避免首次查询即被拒绝。
 *
 * 可用性开关：`Prefs.Keys.SUBAGENT_POLL_GUARD`。**默认关闭**，需在子代理设置页显式开启；
 * 只有度量数据显示轮询确实频繁时才值得打开。
 */
internal class SubAgentPollGuard(
    private val enabled: Boolean = false,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(
        /** 允许下一次查询的最早时刻；在此之前查询判为过于频繁。 */
        var nextAllowedAt: Long,
        var level: Int = 0,
        var strikes: Int = 0,
        var running: Boolean = false,
    )

    data class Rejection(val code: String, val message: String, val nextPollAfterMs: Long)

    private val entries = mutableMapOf<String, Entry>()

    /** 被拒绝的查询总次数，供度量基线使用。 */
    var rejections: Int = 0
        private set

    private var suspendedUntil: Long = 0

    /** 工具是否处于冷静期（此时从本轮目录里摘除）。 */
    val suspended: Boolean
        get() = enabled && now() < suspendedUntil

    fun availableTools(catalog: JSONArray): JSONArray {
        if (!suspended) return catalog
        return JSONArray().also { filtered ->
            for (index in 0 until catalog.length()) {
                val entry = catalog.get(index)
                if ((entry as? JSONObject)?.optJSONObject("function")?.optString("name") != TOOL) filtered.put(entry)
            }
        }
    }

    /** @return null 表示放行。 */
    fun reject(call: AgentModelClient.ToolCall): Rejection? {
        if (!enabled || call.name != TOOL) return null
        val taskId = taskId(call) ?: return null
        val entry = entries[taskId] ?: return null
        if (!entry.running) return null
        val current = now()
        // 已到达（或越过）建议的下一次查询时刻：放行。守规矩地等到建议时间的调用方永远能通过。
        if (current >= entry.nextAllowedAt) return null
        val remaining = entry.nextAllowedAt - current
        entry.strikes += 1
        rejections += 1
        // 只抬高档位，不推后本次窗口：抬高只在下一次真正执行的查询后生效，扩大后续查询间隔。
        entry.level = (entry.level + 1).coerceAtMost(LEVELS.lastIndex)
        // 连续违规（中间没有一次守规矩的查询）才短时摘除工具；到点自动恢复。
        if (entry.strikes >= MAX_STRIKES) suspendedUntil = current + REMOVE_FOR_MS
        return Rejection(CODE, message(remaining), remaining)
    }

    /** 观察一次真正执行的查询结果；只有它才更新状态与节奏。 */
    fun observe(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) {
        if (!enabled || call.name != TOOL) return
        val taskId = taskId(call)
        val parsed = runCatching { JSONObject(result.content) }.getOrNull()
        if (taskId == null) {
            // 列表查询是重新发现 task_id 的合法手段，不参与退避。
            return
        }
        val status = parsed?.optString("status").orEmpty()
        val entry = entries.getOrPut(taskId) { Entry(now()) }
        entry.running = status == "running" || status == "queued"
        if (entry.running) {
            // 本次查询已被放行执行，说明调用方守规矩；重置连续违规计数，只惩罚连续过早查询。
            entry.strikes = 0
            // 按当前档位安排下一次允许查询的时刻；档位随违规增长，从而拉长后续查询间隔。
            entry.nextAllowedAt = now() + LEVELS[entry.level.coerceIn(0, LEVELS.lastIndex)]
        } else {
            // 终态放行：清掉节奏与违规计数，避免过时状态继续限制后续查询。
            entry.level = 0
            entry.strikes = 0
            entry.nextAllowedAt = 0
        }
    }

    private fun taskId(call: AgentModelClient.ToolCall): String? =
        runCatching { JSONObject(call.argumentsJson).optString("task_id").takeIf { it.isNotBlank() } }.getOrNull()

    private fun message(remainingMs: Long): String =
        "子任务仍在运行，同一任务的连续查询过于频繁。请等待约 ${remainingMs} 毫秒后重试，" +
            "或改为继续做其它工作；子任务完成时会主动通知，不需要空转查询。" +
            "终态任务与不带 task_id 的列表查询不受此限制。"

    /** 任务进入终态时解除限制：门禁只约束仍在运行的子任务，绝不阻断结果取回。 */
    fun release(taskId: String) {
        if (taskId.isNotBlank()) entries.remove(taskId)
    }

    companion object {
        const val TOOL = "get_task_result"
        const val CODE = "POLLING_TOO_FREQUENT"
        const val PREF_KEY = Prefs.Keys.SUBAGENT_POLL_GUARD
        const val MAX_STRIKES = 3
        const val REMOVE_FOR_MS = 60_000L
        /** 1 → 2 → 4 → 8 → 16 个首档单位；首档 20 秒，到顶保持。 */
        val LEVELS = longArrayOf(20_000L, 40_000L, 80_000L, 160_000L, 320_000L)

        /** 当前生效的门禁实例；由协调器在派发子代理时安装，用于本轮目录过滤。 */
        @Volatile
        private var installed: SubAgentPollGuard? = null

        fun install(guard: SubAgentPollGuard) {
            installed = guard
        }

        fun uninstall(guard: SubAgentPollGuard) {
            if (installed === guard) installed = null
        }

        /** 挂起期从目录里摘除 `get_task_result`；未安装或未挂起时原样返回。 */
        fun filterAdvertised(catalog: JSONArray): JSONArray = installed?.availableTools(catalog) ?: catalog

        /**
         * 默认关闭，必须在设置页显式开启。
         * 只有度量数据显示主代理确实频繁空转查询时才值得打开。
         */
        fun enabled(): Boolean =
            runCatching { Prefs.isEnabled(Prefs.Keys.SUBAGENT_POLL_GUARD) }.getOrDefault(false)
    }
}
