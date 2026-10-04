package io.github.mangi.eta.agent.model

/**
 * One instance per AgentLoop/run. Counts rejected calls, not provider rounds or call IDs.
 * Unknown tool names share one bucket; neither arguments nor arbitrary names create keys.
 * This guard never executes, rewrites or replays a call. The caller must append the whole
 * tool batch before acting on stopMessage, including valid calls after exhaustion.
 * Declared delegate_task calls retain their separate AgentDelegationArgumentRepair policy.
 */
internal class AgentInvalidToolArgumentsGuard {
    // A null key cannot collide with any declared tool name.
    private val failures = mutableMapOf<String?, Int>()
    var stopMessage: String? = null
        private set

    data class Rejection(val code: String, val message: String)

    fun reject(toolName: String, declared: Boolean, validationError: String): Rejection {
        val key = if (declared) toolName.trim() else null
        val count = ((failures[key] ?: 0) + 1).coerceAtMost(MAX_FAILURES)
        failures[key] = count
        val subject = if (key == null) "未声明工具（所有未知名称共用预算）" else "工具 $key"
        if (count >= MAX_FAILURES) {
            val message = "$STOP_CODE：$subject 的参数校验失败已达 $MAX_FAILURES 次，纠错预算耗尽。" +
                "本次调用未执行；本批工具结果完整配对后按错误重连策略处理，不重放已完成工具。" +
                "不会自动切换环境或重放调用。校验原因：$validationError"
            if (stopMessage == null) stopMessage = message
            return Rejection(STOP_CODE, message)
        }
        return Rejection(
            FAILURE_CODE,
            "$subject 的参数校验失败（$count/$MAX_FAILURES）：$validationError。" +
                "本次调用未执行；请仅修正这个调用，不要重放已成功的工具。" +
                "再次无效达到 $MAX_FAILURES 次将按错误重连策略处理；更换调用 ID、空白或其它工具成功均不会重置此预算。",
        )
    }

    fun validated(toolName: String) {
        // Exhaustion is sticky even if the same batch later contains a corrected call.
        if (stopMessage != null) return
        failures.remove(toolName.trim())
    }

    fun clearExhaustion() {
        stopMessage = null
        failures.clear()
    }

    companion object {
        const val MAX_FAILURES = 3
        const val FAILURE_CODE = "INVALID_TOOL_ARGUMENTS"
        const val STOP_CODE = "INVALID_TOOL_ARGUMENTS_REPAIR_EXHAUSTED"
    }
}
