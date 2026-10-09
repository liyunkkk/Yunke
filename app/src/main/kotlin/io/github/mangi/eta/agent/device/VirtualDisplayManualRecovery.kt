package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手动恢复的只读判定与有界诊断。
 *
 * 这里只做「看到什么就判什么」的纯逻辑：不碰 owner、不碰记录、不发任何交接或释放。
 * 真正危险的动作（交接、释放）仍然只在 [VirtualDisplaySession.finish] 里、按既有预算与证据规则执行。
 */
internal object VirtualDisplayManualRecovery {
    /** 只读核验「记录里那个 owner 进程」的结果。 */
    enum class OwnerProbe { ABSENT, READABLE, UNREADABLE, GONE }

    /** 手动恢复该做什么。 */
    enum class Action { VERIFY_THEN_FINISH, CLEAR_OWNER_GONE, REPORT_UNVERIFIED }

    fun decide(probe: OwnerProbe, budgetBlocked: Boolean): Action = when {
        // owner 已消失：副屏随它一起消失，只需清掉 App 侧记录，绝不补发释放。
        probe == OwnerProbe.GONE -> Action.CLEAR_OWNER_GONE
        // 预算已被 stop()：可能已经发出过释放，再发一次就是重放一个未确认的变更。
        budgetBlocked -> Action.REPORT_UNVERIFIED
        // 其余情况（含连上了但状态读不出）都走既有核验路径：它会给出精确错误码、
        // 保留可重试的会话，并在真发释放前按 owner 的交接标志再判一次。
        // 这里不能因为「这次读不出」就跳过核验——那会让一次失败变成不可重试。
        else -> Action.VERIFY_THEN_FINISH
    }

    /** owner 原始状态的允许清单：只保留符号化字段，绝不回显 token、凭据或任务内容。 */
    private val SUMMARY_KEYS = listOf(
        // 顺序即重要性：先给四个交接标志与三个任务清单，截断时丢的只是尾部的环境字段。
        "finishing", "handoffComplete", "releaseAttempted", "mutationUncertain", "handoffCleanupOnly",
        "retainedTaskIds", "liveTaskIds", "goneTaskIds", "sourceState", "sourceEmpty", "sourceTaskCount",
        "sourcePackagesKnown", "sourceError", "ready", "session", "displayId", "ok", "op",
    )

    /** 摘要长度上限：够看出是哪一项不满足，又不会把记录撑大。 */
    const val SUMMARY_LIMIT = 320

    /** 错误码回显上限：错误码是符号化字段，不需要更长。 */
    private const val ERROR_CODE_LIMIT = 40

    /**
     * owner 回报的有界摘要。[ok] 与 [errorCode] 可选：带上它们才能分辨「读不到状态」是
     * 连接层失败（ok=false）还是字段缺失；不传时输出与旧版逐字一致。
     */
    fun ownerStatusSummary(status: JSONObject?, ok: Boolean? = null, errorCode: String = ""): String {
        val head = buildString {
            if (ok != null) append("ok=").append(ok).append(' ')
            if (errorCode.isNotBlank()) append("error=").append(errorCode.take(ERROR_CODE_LIMIT)).append(' ')
        }
        if (status == null) return (head + "owner_status=missing").take(SUMMARY_LIMIT)
        val parts = ArrayList<String>(SUMMARY_KEYS.size)
        SUMMARY_KEYS.forEach { key ->
            when {
                !status.has(key) -> parts += "$key=absent"
                status.isNull(key) -> parts += "$key=null"
                else -> parts += "$key=" + summarize(status.opt(key))
            }
        }
        return (head + parts.joinToString(" ")).take(SUMMARY_LIMIT)
    }

    private fun summarize(value: Any?): String = when (value) {
        is Boolean, is Number -> value.toString()
        is JSONArray -> "[${value.length()}]"
        is JSONObject -> "{${value.length()}}"
        is String -> value.take(24).filter { it.code in 0x20..0x7e }
        else -> "?"
    }
}
