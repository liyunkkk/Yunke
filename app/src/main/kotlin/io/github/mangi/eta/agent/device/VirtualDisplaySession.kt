package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentAfterActionSummary
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject

/** Single-device owner. Recovery errors never authorize a second display. */
internal object VirtualDisplaySession {
    const val NOT_READY = "VIRTUAL_DISPLAY_HANDOFF_NOT_READY"
    /**
     * 副屏一次观察附带的最大节点数。
     *
     * 40 太容易在列表页被截断（truncated=true），模型只能再观察一次才拿得到目标节点；
     * 60 在 1080x2400 的常见界面上基本够用，省下的那一步比多出的 token 更值。
     */
    private const val NODE_LIMIT = 60
    /** 动作后回读的节点上限：只取节点、不取截图，控制每步附带观察的开销。 */
    private const val AFTER_ACTION_NODE_LIMIT = 40
    /** 动作后回读前的稳定等待：给副屏一次渲染机会，避免读到动作前的旧树。 */
    private const val AFTER_ACTION_SETTLE_MS = 350L
    /** 副屏 replace_text / clear_text 的文本上限，与主屏一致。 */
    private const val MAX_TEXT_CHARS = 4_000
    /** 空闲巡检间隔与默认空闲上限（分钟，0=永不）。 */
    private const val IDLE_CHECK_INTERVAL_MS = 30_000L
    private const val DEFAULT_IDLE_TIMEOUT_MINUTES = 20
    private const val IDLE_TIMEOUT_PREF = "agent_virtual_display_idle_timeout_minutes"
    private const val FLOATING_WINDOW_PREF = "agent_virtual_display_floating_window"
    /** 冲突时是否直接接管（默认关闭：先问用户）。 */
    private const val CONFLICT_TAKEOVER_PREF = "agent_virtual_display_conflict_takeover"
    private const val AGENT_PREFERENCES = "eta_agent_preferences"
    /** 只读镜像页展示的最近操作条数。 */
    private const val TRACE_LIMIT = 100
    private class Session(var client: VirtualDisplayOwnerClient? = null, var phase: String = "starting") {
        val kept = linkedSetOf<Int>()
        val packages = linkedMapOf<String, Set<Int>>()
        val observation = VirtualDisplayObservation()
        val uiTreeAvailability = VirtualDisplayUiTreeAvailability()
        val previewExcludedPackages = linkedSetOf<String>()
        var closedRun = false
        var cleanupOnly = false
        var abortCleanupAttempted = false
        var persisted = false
        /** 本次副屏会话是否已经尝试过无障碍强制自愈：最多一次，避免反复重绑。 */
        var accessibilityRecoveryAttempted = false
        /** 最近一次 Agent 活动时间；空闲巡检据此判断是否自动收尾。 */
        var lastActivityMs = System.currentTimeMillis()
        /** 只读镜像页用的最近操作轨迹（不含节点内容）。 */
        val recentActions = ArrayDeque<String>()
        /** 本会话从主屏接管过来的应用（包名 -> taskId）：收尾前必须先搬回主屏。 */
        val takenOver = linkedMapOf<String, Int>()
        /** 副屏文本走剪贴板回退时的备份：写入前记住原内容，动作后尽量还原。 */
        var clipboardBackup: String? = null
        var clipboardBackupPresent = false
        var clipboardWritten: String? = null
        var receipt: JSONObject? = null
        /** Shared across finish/onRunClosed for this run so automatic retries cannot multiply. */
        val handoffBudget = VirtualDisplayHandoffRetry.Budget()
        // Retain the pre-attempt baseline across reentry/adoption; never replace it with a new owner.
        var handoffState: VirtualDisplayHandoffRetry.OwnerState? = null
        var handoffSelection: Set<Int>? = null
        // Bounded history survives a held-session adoption; includes successful later attempts.
        val handoffAttempts = JSONArray()
        var handoffDiagnostics: VirtualDisplayHandoffDiagnostics.Failure? = null
    }
    private val sessions = linkedMapOf<String, Session>()
    private var recoveryContext: Context? = null
    private var idleMonitor: java.util.concurrent.ScheduledExecutorService? = null
    private const val RECOVERY_PREFS = "virtual_display_owner_recovery"
    private fun recoveryPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)

    /** 失败时把 owner 回报的有界摘要写进持久记录：不新增日志，但下次能看见失败现场。 */
    private fun persistOwnerDiag(context: Context?, response: OwnerResponse?) {
        val target = context?.applicationContext ?: recoveryContext ?: return
        val summary = runCatching {
            VirtualDisplayManualRecovery.ownerStatusSummary(response?.json, response?.ok, response?.errorCode.orEmpty())
        }.getOrNull()
        if (summary.isNullOrBlank()) return
        runCatching {
            recoveryPrefs(target).edit().putString(VirtualDisplayRecoveryRecord.DIAG, summary).commit()
        }
    }

    /** 记录里的副屏是否仍在系统里：按 uniqueId 里的 eta-vd-<hash> 段与当前 display 名比对。 */
    private fun ownerDisplayStillAlive(context: Context?, uniqueId: String): Boolean {
        val name = VirtualDisplayManualRecovery.displayNameFromUniqueId(uniqueId) ?: return true
        val ctx = context?.applicationContext ?: recoveryContext ?: return true
        return runCatching {
            val manager = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            manager == null || manager.displays.any { it.name == name }
        }.getOrDefault(true)
    }

    /**
     * 读不出 owner 状态时的收口：只有确认 owner 真的消失（记录来自上次开机，或 socket 连不上且
     * 当前没有任何同名副屏）才清 App 侧记录并如实回报「副屏已不存在」；否则返回 null，
     * 让调用方保持原有的保守失败路径（保留证据、不重放未确认的释放）。
     */
    private fun clearWhenOwnerVerifiedGone(context: Context?, s: Session): JSONObject? {
        val ctx = context ?: recoveryContext ?: return null
        if (VirtualDisplayManualRecovery.decideLeftover(probeRecoveredOwner(ctx)) !=
            VirtualDisplayManualRecovery.Action.CLEAR_OWNER_GONE
        ) return null
        s.client?.close()
        s.phase = "finished"
        val cleared = runCatching {
            recoveryPrefs(ctx).edit().clear().commit()
        }.getOrDefault(false)
        return if (cleared) reply(true).put("released", true).put("cleared", "OWNER_GONE")
        else reply(false, "RECOVERY_RECORD_CLEAR_FAILED").put("released", true)
    }

    /**
     * 只读核验记录里的 owner 进程：连得上且状态可判定＝READABLE，连不上/对端已不是它＝GONE。
     * 不发送任何交接或释放；探测用的连接用完即关。
     */
    private fun probeRecoveredOwner(context: Context): VirtualDisplayManualRecovery.OwnerProbe {
        val saved = runCatching { recoveryPrefs(context) }.getOrNull()
            ?: return VirtualDisplayManualRecovery.OwnerProbe.ABSENT
        val fields = runCatching { saved.all }.getOrNull()
            ?: return VirtualDisplayManualRecovery.OwnerProbe.ABSENT
        if (fields.isEmpty()) return VirtualDisplayManualRecovery.OwnerProbe.ABSENT
        val boot = bootId() ?: return VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE
        // 跨启动的记录：记录里的 owner 一定已经随上次开机消失。
        if (VirtualDisplayRecoveryRecord.previousBoot(fields["boot"], boot))
            return VirtualDisplayManualRecovery.OwnerProbe.GONE
        val record = runCatching { VirtualDisplayRecoveryRecord.decode(fields) }.getOrNull()
            ?: return VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE
        return try {
            val c = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                record.displayId, record.uniqueId, record.token, record.run)
            try {
                if (handoffState(c, c.status()) == null) VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE
                else VirtualDisplayManualRecovery.OwnerProbe.READABLE
            } finally {
                runCatching { c.close() }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE
        } catch (ex: VirtualDisplayRecoveryException) {
            // connect/peer 阶段失败＝记录里的 owner 已不在（副屏随它一起消失）；
            // 但只有当前确实没有任何同名副屏时才算「已消失」，避免把暂时连不上误判成丢了。
            if ((ex.stage == "connect" || ex.stage == "peer") &&
                !ownerDisplayStillAlive(context, record.uniqueId)
            ) VirtualDisplayManualRecovery.OwnerProbe.GONE
            else VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE
        } catch (_: Exception) {
            VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE
        }
    }
    private fun bootId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            .takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
    }.getOrNull()
    private fun reply(ok: Boolean, code: String = "", detail: String = "") =
        JSONObject().put("ok",ok).put("error",code).put("message",detail)
    private fun body(response: OwnerResponse): JSONObject = response.json ?: reply(false,response.errorCode)
    private fun ids(value: JSONArray?): Set<Int>? = value?.let {
        VirtualDisplayRecoveryPolicy.taskIds((0 until it.length()).map(it::opt))
    }
    private fun flags(state: JSONObject): VirtualDisplayRecoveryPolicy.Flags? {
        val values = listOf("finishing", "handoffComplete", "releaseAttempted", "mutationUncertain", "sourceEmpty")
            .map { state.opt(it) }
        if (values.any { it !is Boolean }) return null
        return VirtualDisplayRecoveryPolicy.Flags(values[0] as Boolean, values[1] as Boolean,
            values[2] as Boolean, values[3] as Boolean, values[4] as Boolean)
    }
    private fun handoffState(c: VirtualDisplayOwnerClient, response: OwnerResponse): VirtualDisplayHandoffRetry.OwnerState? {
        val data = response.json ?: return null
        return VirtualDisplayHandoffEvidence.state(
            identity = VirtualDisplayHandoffRetry.OwnerIdentity(c.socketName, c.ownerPid, c.displayId, c.uniqueId),
            authenticatedConnection = c.isAlive,
            statusOk = response.ok && response.op == VirtualDisplayOwnerProtocol.OP_STATUS,
            flags = flags(data),
            retainedTaskIds = ids(data.optJSONArray("retainedTaskIds")),
            field = data::opt,
        )
    }
    /** 冲突时是否直接接管（用户偏好；默认关闭，先问一次）。 */
    private fun conflictTakeoverPreferred(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(AGENT_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(CONFLICT_TAKEOVER_PREF, false)
    }.getOrDefault(false)

    /**
     * 释放副屏前归还接管过来的 task。
     *
     * 返回 null 表示名单已清空、可以继续收尾；非 null 是一份「不释放」的回执：
     * 只要还有应用没搬回主屏，就绝不销毁副屏——那会把用户正在用的应用一起弄丢。
     * 归还本身可重试（每次都逐个核对），因此这里不消耗交接预算、也不标 uncertain。
     */
    private fun returnTakenOver(c: VirtualDisplayOwnerClient, s: Session): JSONObject? {
        // App 侧名单可能因为进程重启而丢失；owner 侧还记着就必须照样归还，否则 release 会
        // 以 SOURCE_NOT_EMPTY 失败，用户的应用会一直留在副屏上。
        val ownerTaken = runCatching { c.status() }.getOrNull()
            ?.let { body(it).optJSONArray("takenOverTaskIds") }
        if (s.takenOver.isEmpty() && (ownerTaken == null || ownerTaken.length() == 0)) return null
        val response = runCatching { c.takeoverReturn() }.getOrNull()
        val names = if (s.takenOver.isEmpty()) ownerTaken ?: JSONArray() else JSONArray(s.takenOver.keys.toList())
        if (response == null || !response.ok) {
            return reply(false, "TAKEOVER_RETURN_FAILED")
                .put("released", false)
                .put("owner_error", response?.errorCode.orEmpty())
                .put("taken_over", names)
                .put("note", "接管的应用还没搬回主屏；副屏保持打开，避免把它一起销毁。可重试 finish_virtual_session。")
        }
        val failed = body(response).optJSONArray("failedTaskIds")
        if (failed != null && failed.length() > 0) {
            return reply(false, "TAKEOVER_RETURN_INCOMPLETE")
                .put("released", false)
                .put("failed_task_ids", failed)
                .put("taken_over", names)
                .put("note", "还有应用没搬回主屏；副屏保持打开，避免把它一起销毁。可重试 finish_virtual_session。")
        }
        s.takenOver.clear()
        return null
    }

    /** 只读探测结果：state 为 null 表示这次读不出已知状态，response 保留 owner 的原始回报供诊断。 */
    private class HandoffProbe(val state: VirtualDisplayHandoffRetry.OwnerState?, val response: OwnerResponse?)

    private fun probeHandoffState(c: VirtualDisplayOwnerClient): HandoffProbe =
        try {
            val raw = c.status()
            HandoffProbe(handoffState(c, raw), raw)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            HandoffProbe(null, null)
        } catch (_: Exception) {
            HandoffProbe(null, null)
        }

    private fun freshHandoffState(c: VirtualDisplayOwnerClient): VirtualDisplayHandoffRetry.OwnerState? =
        probeHandoffState(c).state

    private fun fail(s: Session, code: String, detail: String = ""): JSONObject {
        s.handoffBudget.stop()
        s.phase = "uncertain"
        return VirtualDisplayHandoffDiagnostics.annotate(
            reply(false, code, detail).withHandoffAttempts(s), s.handoffDiagnostics, uncertain = true,
        ).also { s.receipt = it }
    }
    /**
     * finish 可能被重入。首次失败往往携带具体诊断（例如 owner 的 HANDOFF_PREFLIGHT_FAILED）；
     * 之后的重入不得把这份诊断降级成笼统的 RECOVERY_UNCERTAIN。
     */
    private fun failPreservingPrior(s: Session, code: String): JSONObject {
        s.handoffBudget.stop()
        val prior = s.receipt
        val priorCode = prior?.takeIf { !it.optBoolean("ok") }?.optString("error").orEmpty()
        if (code in setOf("RECOVERY_UNCERTAIN", "OWNER_STATE_UNKNOWN") && priorCode.isNotBlank()) {
            s.phase = "uncertain"
            return VirtualDisplayHandoffDiagnostics.preservedReceipt(prior!!)
        }
        return fail(s, code)
    }
    /** 只转发无歧义的安全细节；绝不回显 token、Intent 转储或数据 URI。 */
    private fun safeOwnerDetail(response: OwnerResponse): String {
        val raw = response.json?.opt("message") as? String ?: return ""
        val collapsed = raw.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
        if (collapsed.isEmpty() || collapsed.length > 180) return ""
        // Only the symbolic handoff phase/type and integer task tallies are allowed.
        if (!collapsed.matches(Regex("[A-Za-z0-9_:#.\\-]+:[A-Za-z0-9_$]+; ?moved=\\[[0-9, ]*\\] removed=\\[[0-9, ]*\\]"))) return ""
        return collapsed
    }
    private fun JSONObject.withHandoffAttempts(s: Session): JSONObject = apply {
        if (s.handoffAttempts.length() > 0) {
            put("handoffAttempts", JSONArray(s.handoffAttempts.toString()))
        }
    }

    private fun recordHandoff(s: Session, response: OwnerResponse, completed: Boolean) {
        val diagnostics = VirtualDisplayHandoffDiagnostics.sanitizeFailure(response.json)
        s.handoffDiagnostics = diagnostics
        val rawSamples = response.json?.optJSONArray("focusSamples")
        val samples = JSONArray()
        if (rawSamples != null) for (i in 0 until minOf(rawSamples.length(), 4)) {
            val token = rawSamples.opt(i) as? String ?: continue
            if (token.length <= 80 && token.matches(Regex("OK|Focus_[A-Z_]+"))) samples.put(token)
        }
        if (s.handoffAttempts.length() >= 6) s.handoffAttempts.remove(0)
        s.handoffAttempts.put(VirtualDisplayHandoffDiagnostics.annotate(JSONObject()
            .put("ok", completed)
            .put("error", if (completed) "" else VirtualDisplayHandoffDiagnostics.safeHandoffError(response.errorCode))
            .put("message", if (completed) "" else safeOwnerDetail(response))
            .put("focusSamples", samples), diagnostics))
    }

    private fun clearReleased(context: Context?, s: Session): JSONObject {
        s.phase = "finished"
        s.client?.close()
        val cleared = context != null && runCatching {
            recoveryPrefs(context).edit().clear().commit()
        }.getOrDefault(false)
        return if (cleared) reply(true).put("released", true)
        else reply(false, "RECOVERY_RECORD_CLEAR_FAILED").put("released", true)
    }
    @Synchronized fun start(context: Context, runId: String, createIfMissing: Boolean = true): JSONObject {
        recoveryContext = context.applicationContext
        if (runId.isBlank()) return reply(false, "RUN_ID_REQUIRED")
        sessions[runId]?.let { s ->
            if (s.phase == "finished") return reply(false, "SESSION_FINISHED")
            // A cancelled run can still select delivery tasks and finish through the owner.
            // Do not restore GUI access or reset this run's automatic retry budget.
            // An empty kept set stays empty: recovery must not promote every launched task.
            if (VirtualDisplayRecoveryPolicy.canRecoverExistingRun(s.phase, s.closedRun)) {
                s.cleanupOnly = true
                return reply(true).put("recovered", true).put("cleanup_only", true).put("phase", s.phase)
            }
            return if (s.phase == "active") reply(true).put("phase", s.phase)
            else reply(false, "RECOVERY_REQUIRED").put("phase", s.phase)
        }
        val others = sessions.values.filter { it.phase != "finished" }
        if (others.any { !it.closedRun } || others.size > 1) return reply(false, "VIRTUAL_SESSION_BUSY")
        if (others.size == 1) {
            val s = others.single()
            // 上一次收尾已经失败（uncertain）或预算已被 stop()：这个会话不可能再被安全地用一次。
            // 先只读核验 owner：确认它已消失就丢掉残留会话并清记录，让本轮直接开新会话；
            // 否则如实回报，绝不复用一次可能已经发出过释放的会话。
            if (s.phase == "uncertain" || s.handoffBudget.blocked) {
                if (VirtualDisplayManualRecovery.decideLeftover(probeRecoveredOwner(context)) !=
                    VirtualDisplayManualRecovery.Action.CLEAR_OWNER_GONE
                ) {
                    sessions.entries.removeAll { it.value === s }
                    s.cleanupOnly = true
                    sessions[runId] = s
                    return reply(false, "RECOVERY_NEEDS_OWNER_VERIFICATION").put("phase", s.phase)
                }
                // owner 与副屏都已确认消失：清掉 App 侧记录，按「没有残留」继续，本轮直接开新会话。
                sessions.entries.removeAll { it.value === s }
                runCatching { recoveryPrefs(context).edit().clear().commit() }
            } else {
                sessions.entries.removeAll { it.value === s }
                s.cleanupOnly = true
                sessions[runId] = s
                // A deliberate later run gets a fresh clean-rejection budget, never mutation replay.
                s.handoffBudget.reset()
                return reply(true).put("recovered", true).put("cleanup_only", true).put("phase", s.phase)
            }
        }
        var recoveryStage = "record_read"
        var lastOwnerStatus: OwnerResponse? = null
        val saved = try { recoveryPrefs(context) } catch (ex: Exception) {
            val e = VirtualDisplayRecoveryException.classify(recoveryStage, ex)
            return reply(false, e.code).put("recovery_stage", e.stage)
        }
        if (!createIfMissing && runCatching { saved.all.isEmpty() }.getOrDefault(false)) return reply(false, "NO_VIRTUAL_SESSION")
        val s = Session()
        sessions[runId] = s
        val boot = bootId() ?: return fail(s, "BOOT_ID_UNAVAILABLE")
        try {
            val fields = saved.all
            if (fields.isNotEmpty() && VirtualDisplayRecoveryRecord.previousBoot(fields["boot"], boot)) {
                recoveryStage = "record_write"
                if (!saved.edit().clear().commit()) return fail(s, "RECOVERY_STATE_UNWRITABLE")
            } else if (fields.isNotEmpty()) {
                recoveryStage = "record_decode"
                val record = VirtualDisplayRecoveryRecord.decode(fields)
                if (record.boot != boot) throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_BOOT_MISMATCH")
                if (record.mutationBarrier != null) return fail(s, "MUTATION_UNCERTAIN_NO_REPLAY")
                recoveryStage = "connect"
                val c = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                    record.displayId, record.uniqueId, record.token, record.run)
                s.client = c
                recoveryStage = "status"
                val state = c.status()
                lastOwnerStatus = state
                recoveryStage = "handoff_state"
                val observed = handoffState(c, state)
                    ?: throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_HANDOFF_STATE_INVALID")
                val registered = observed.retainedTaskIds
                recoveryStage = "selection"
                // A missing kept field is an empty delivery selection, not "every retained task".
                val restored = record.kept?.let { ids(JSONArray(it))
                    ?: throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_SELECTION_INVALID") } ?: emptySet()
                if (!registered.containsAll(restored)) throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_SELECTION_INVALID")
                s.kept.addAll(restored)
                s.persisted = true
                s.cleanupOnly = true
                s.closedRun = true
                s.phase = "held"
                return body(state).put("recovered", true).put("cleanup_only", true).put("run_id", runId)
            }
        } catch (ex: Exception) {
            val e = VirtualDisplayRecoveryException.classify(recoveryStage, ex)
            AndroidAgentLogger.warn("Virtual display recovery stage=${e.stage} code=${e.code} field=${e.field} type=${e.exceptionType}")
            persistOwnerDiag(context, lastOwnerStatus)
            // This block authenticates and reads only; it has not sent a handoff or release.
            s.phase = "held"; s.closedRun = true; s.cleanupOnly = true
            return reply(false, e.code).put("recovery_stage", e.stage).put("recovery_field", e.field)
                .put("mutation_uncertain", false).put("automatic_retry_allowed", false).also { s.receipt = it }
        }
        // Manual recovery must never create a display merely to close it.
        if (!createIfMissing) {
            sessions.remove(runId)
            return reply(false, "NO_VIRTUAL_SESSION")
        }
        // 开关打开时随会话自动挂上只读悬浮小窗；会话结束后小窗会自行收掉。
        if (floatingWindowEnabled(context)) {
            runCatching { io.github.mangi.eta.agent.overlay.VirtualDisplayFloatingWindow.show(context) }
        }
        return when (val started = VirtualDisplayOwnerClient.start(context, AndroidAgentLogger)) {
            is OwnerStartResult.Failed -> fail(s, started.errorCode)
            is OwnerStartResult.Ready -> {
                val c = started.client
                s.client = c
                s.persisted = runCatching { saved.edit().putString("socket", c.socketName)
                    .putLong("pid", c.ownerPid).putInt("display", c.displayId)
                    .putString("unique", c.uniqueId).putString("token", c.recoveryToken)
                    .putString("run", runId).putString("boot", boot).commit() }.getOrDefault(false)
                if (!s.persisted) return fail(s, "RECOVERY_STATE_UNWRITABLE")
                val state = c.status()
                if (handoffState(c, state) == null) fail(s, "OWNER_STATE_UNKNOWN")
                else { s.phase = "active"; body(state).put("run_id", runId) }
            }
        }
    }
    /** Read only app-owned metadata; never treat the unrelated port-3070 daemon as this owner. */
    @Synchronized fun recoveryStatus(context: Context): JSONObject {
        return try {
            val pending = sessions.values.filter { it.phase != "finished" }
            val saved = recoveryPrefs(context)
            if (pending.isEmpty() && saved.all.isEmpty()) return reply(true).put("present", false)
            val s = pending.singleOrNull()
            val busy = pending.any { !it.closedRun }
            reply(true).put("present", true).put("manager", "eta_owner")
                .put("displayId", s?.client?.displayId ?: saved.getInt("display", -1))
                .put("phase", s?.phase ?: "recovery_pending")
                .put("busy", busy).put("recoverable", !busy && pending.size <= 1)
                .put("lastError", s?.receipt?.optString("error").orEmpty())
                .put("lastDetail", s?.receipt?.optString("message").orEmpty().ifBlank {
                    // 进程重启后内存回执没了：用失败时写进记录的有界 owner 摘要补上，方便定位。
                    runCatching { saved.getString(VirtualDisplayRecoveryRecord.DIAG, "").orEmpty() }
                        .getOrDefault("")
                })
        } catch (_: Exception) { reply(false, "RECOVERY_STATE_UNREADABLE") }
    }

    /** Lists only the authenticated current owner, not Android's unrelated displays. */
    @Synchronized fun previewDisplays(context: Context): VirtualDisplayPreviewHttpServer.Displays {
        val result = previewRead(context, null, capture = false)
        return when (result.error) {
            "NO_VIRTUAL_SESSION" -> VirtualDisplayPreviewHttpServer.Displays()
            "" -> VirtualDisplayPreviewHttpServer.Displays(listOf(
                VirtualDisplayPreviewHttpServer.Display(result.displayId, result.uniqueId, result.phase)))
            else -> VirtualDisplayPreviewHttpServer.Displays(error = result.error)
        }
    }

    /** A snapshot only: no start/adoption, GUI observation contract, handoff or release. */
    @Synchronized fun previewFrame(
        context: Context,
        selected: VirtualDisplayPreviewHttpServer.Identity? = null,
    ): VirtualDisplayPreviewHttpServer.Frame = previewRead(context, selected, capture = true)

    /** Called under the Session monitor. A reconnect is borrowed, never installed/adopted. */
    private fun previewRead(
        context: Context,
        selected: VirtualDisplayPreviewHttpServer.Identity?,
        capture: Boolean,
    ): VirtualDisplayPreviewHttpServer.Frame {
        fun denied(code: String) = VirtualDisplayPreviewHttpServer.Frame(error = code)
        if (selected != null && !VirtualDisplayPreviewHttpServer.validIdentity(selected))
            return denied("PREVIEW_IDENTITY_INVALID")
        val pending = sessions.values.filter { it.phase != "finished" }
        if (pending.size > 1) return denied("OWNER_STATE_UNKNOWN")
        val session = pending.singleOrNull()
        var borrowed = false
        var client = session?.client?.takeIf { it.isAlive }
        try {
            if (client == null) {
                val saved = recoveryPrefs(context)
                if (saved.all.isEmpty()) return denied(if (session == null) "NO_VIRTUAL_SESSION" else "RECOVERY_UNCERTAIN")
                val boot = bootId() ?: return denied("BOOT_ID_UNAVAILABLE")
                if (saved.getString("boot", null) != boot) return denied("RECOVERY_UNCERTAIN")
                client = VirtualDisplayOwnerClient.reconnect(AndroidAgentLogger,
                    saved.getString("socket", "").orEmpty(), saved.getLong("pid", -1),
                    saved.getInt("display", -1), saved.getString("unique", "").orEmpty(),
                    saved.getString("token", "").orEmpty(), saved.getString("run", "").orEmpty())
                    ?: return denied("RECOVERY_UNCERTAIN")
                borrowed = true
            }
            val owner = client ?: return denied("RECOVERY_UNCERTAIN")
            val identity = VirtualDisplayPreviewHttpServer.Identity(owner.displayId, owner.uniqueId)
            if (!VirtualDisplayPreviewHttpServer.validIdentity(identity)) return denied("OWNER_STATE_UNKNOWN")
            if (selected != null && selected != identity) return denied("PREVIEW_DISPLAY_GONE")
            fun matchesOwner(response: OwnerResponse): Boolean {
                val state = response.json ?: return false
                return response.ok && state.opt("displayId") == identity.displayId &&
                    state.optString("uniqueId") == identity.uniqueId
            }
            fun sourcePackages(response: OwnerResponse): Set<String>? {
                if (!matchesOwner(response)) return null
                val packages = response.json?.optJSONArray("sourcePackages") ?: return null
                val result = linkedSetOf<String>()
                for (i in 0 until packages.length()) {
                    val pkg = packages.opt(i) as? String ?: return null
                    if (pkg.isBlank()) return null
                    result.add(pkg)
                }
                return result
            }
            val state = owner.status()
            if (!matchesOwner(state)) return denied("OWNER_STATE_UNKNOWN")
            val phase = session?.phase ?: "recovery_pending"
            if (!capture) return VirtualDisplayPreviewHttpServer.Frame(
                displayId = identity.displayId, uniqueId = identity.uniqueId, phase = phase)
            val excluded = session?.previewExcludedPackages.orEmpty() + context.packageName
            val before = sourcePackages(state) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (before.isEmpty()) return denied("NO_FRAME")
            if (before.any { it in excluded }) return denied("SCREENSHOT_EXCLUDED_PACKAGE")
            val shot = owner.snapshot()
            if (!shot.ok) return denied("NO_FRAME")
            val afterState = owner.status()
            if (!matchesOwner(afterState)) return denied("OWNER_STATE_UNKNOWN")
            val after = sourcePackages(afterState) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (after != before || after.any { it in excluded }) return denied("SCREEN_CONTENT_CHANGED")
            val data = shot.json ?: return denied("FRAME_INVALID")
            val encoded = data.optString("data")
            if (data.opt("displayId") != owner.displayId || data.optString("format") != "png" ||
                encoded.length !in 1..(VirtualDisplayPreviewHttpServer.MAX_FRAME_BYTES * 4 / 3 + 16))
                return denied("FRAME_INVALID")
            val png = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            if (!VirtualDisplayPreviewHttpServer.validPng(png)) return denied("FRAME_INVALID")
            return VirtualDisplayPreviewHttpServer.Frame(png, identity.displayId, phase, uniqueId = identity.uniqueId)
        } catch (_: Exception) {
            return denied("PREVIEW_UNAVAILABLE")
        } finally {
            // Closing a borrowed read connection never releases the display or its owner.
            if (borrowed) client?.close()
        }
    }

    private val manualCloser = VirtualDisplayManualClose()

    @Synchronized fun prepareManualClose(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity): VirtualDisplayManualClose.Result =
        manualCloseOperation(context, selected, null)

    @Synchronized fun commitManualClose(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity, nonce: String): VirtualDisplayManualClose.Result =
        manualCloseOperation(context, selected, nonce)

    /** Entire prepare/commit, including evidence and journal, is under the Session monitor. */
    private fun manualCloseOperation(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity, nonce: String?): VirtualDisplayManualClose.Result {
        fun blocked(code: String) = VirtualDisplayManualClose.Result("blocked", code)
        if (!VirtualDisplayPreviewHttpServer.validIdentity(selected)) return blocked("PREVIEW_IDENTITY_INVALID")
        var borrowed = false
        var client: VirtualDisplayOwnerClient? = null
        var attempted = false
        var stage = "record_read"
        try {
            val saved = recoveryPrefs(context)
            val fields = saved.all
            if (fields.isEmpty()) return blocked("NO_VIRTUAL_SESSION")
            stage = "record_decode"
            val record = VirtualDisplayRecoveryRecord.decode(fields)
            if (record.displayId != selected.displayId || record.uniqueId != selected.uniqueId) return blocked("PREVIEW_DISPLAY_GONE")
            val currentBoot = bootId() ?: return blocked("BOOT_ID_UNAVAILABLE")
            if (record.boot != currentBoot) return blocked("RECOVERY_BOOT_MISMATCH")
            val pending = sessions.values.filter { it.phase != "finished" }
            if (pending.size > 1) return blocked("OWNER_STATE_UNKNOWN")
            val session = pending.singleOrNull()
            if (pending.any { !it.closedRun }) return blocked("ACTIVE_AGENT_OWNER")
            if (record.mutationBarrier != null) return blocked("MUTATION_UNCERTAIN_NO_REPLAY")
            stage = "connect"
            client = session?.client?.takeIf { it.isAlive }
            if (client == null) {
                client = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                    record.displayId, record.uniqueId, record.token, record.run)
                borrowed = true
            }
            val owner = requireNotNull(client)
            if (owner.ownerPid != record.pid || owner.socketName != record.socket ||
                owner.displayId != record.displayId || owner.uniqueId != record.uniqueId) return blocked("RECOVERY_OWNER_IDENTITY_MISMATCH")
            stage = "status"
            val backend = object : VirtualDisplayManualClose.Backend {
                override fun evidence(): VirtualDisplayManualClose.Evidence {
                    val nowRecord = VirtualDisplayRecoveryRecord.decode(saved.all)
                    val state = owner.status()
                    val data = state.json
                    val observed = handoffState(owner, state)
                    val sameRecord = nowRecord.key() == record.key() && nowRecord.token == record.token && nowRecord.run == record.run
                    val retainedIds = observed?.retainedTaskIds
                    val liveIds = observed?.liveTaskIds
                    val goneIds = observed?.goneTaskIds
                    val escapedCount = if (retainedIds != null && liveIds != null && goneIds != null)
                        (retainedIds - liveIds - goneIds).size else null
                    return VirtualDisplayManualClose.Evidence(
                        record.key(), bootId(), observed != null && sameRecord,
                        sessions.values.any { !it.closedRun && it.phase != "finished" },
                        data?.opt("sourceState") as? String, data?.opt("sourceTaskCount") as? Int,
                        data?.opt("sourceEmpty") as? Boolean, retainedIds?.size,
                        data?.opt("finishing") as? Boolean, data?.opt("handoffComplete") as? Boolean,
                        data?.opt("releaseAttempted") as? Boolean, data?.opt("mutationUncertain") as? Boolean,
                        nowRecord.mutationBarrier != null || (session?.handoffState != null &&
                            session.receipt?.opt("mutation_uncertain") == true),
                        liveIds?.size, goneIds?.size, escapedCount,
                    )
                }
                override fun markAttempt(): Boolean {
                    val now = VirtualDisplayRecoveryRecord.decode(saved.all)
                    if (now.key() != record.key() || now.token != record.token || now.run != record.run || now.mutationBarrier != null) return false
                    val persisted = saved.edit().putString(VirtualDisplayRecoveryRecord.BARRIER, "manual_release_pending").commit()
                    if (persisted) { attempted = true; session?.handoffBudget?.stop() }
                    return persisted
                }
                /** One empty-taskIds cleanup. No retry; a false result must not release. */
                override fun cleanup(): Boolean {
                    val before = handoffState(owner, owner.status()) ?: return false
                    if (!VirtualDisplayHandoffRetry.freshStateAllowsCleanup(before, before)) return false
                    val handoff = owner.handoff(mapOf("taskIds" to JSONArray()))
                    val completed = VirtualDisplayHandoffEvidence.completed(
                        authenticatedConnection = owner.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        selectedIds = emptySet(),
                        retainedIds = before.retainedTaskIds,
                        keptIds = ids(handoff.json?.optJSONArray("keptTaskIds")),
                        removedIds = ids(handoff.json?.optJSONArray("removedTaskIds")),
                        field = { handoff.json?.opt(it) },
                        goneIds = ids(handoff.json?.optJSONArray("goneTaskIds")),
                    )
                    if (session != null) recordHandoff(session, handoff, completed)
                    return completed
                }
                override fun release(): Boolean = owner.release().ok
                override fun confirmGone(): Boolean {
                    val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return false
                    repeat(40) {
                        if (bootId() != record.boot) return false
                        val originalDisplayGone = manager.getDisplay(record.displayId) == null // Reused IDs remain unconfirmed.
                        // Signal zero is a read-only existence check, never a process termination.
                        val originalProcessGone = try {
                            android.system.Os.kill(record.pid.toInt(), 0); false
                        } catch (ex: android.system.ErrnoException) {
                            ex.errno == android.system.OsConstants.ESRCH
                        }
                        if (originalDisplayGone && originalProcessGone) return true
                        Thread.sleep(50)
                    }
                    return false
                }
                override fun clearConfirmed(): Boolean {
                    val now = VirtualDisplayRecoveryRecord.decode(saved.all)
                    if (now.key() != record.key() || now.token != record.token || now.run != record.run ||
                        now.mutationBarrier != "manual_release_pending") return false
                    val cleared = saved.edit().clear().commit()
                    if (cleared) {
                        session?.let {
                            it.phase = "finished"
                            it.receipt = reply(true).put("released", true).put("manual_close", true)
                        }
                        owner.close()
                    }
                    return cleared
                }
            }
            return if (nonce == null) manualCloser.prepare(backend) else manualCloser.close(nonce, backend)
        } catch (ex: Exception) {
            val failure = VirtualDisplayRecoveryException.classify(stage, ex)
            AndroidAgentLogger.warn("Virtual display manual close stage=${failure.stage} code=${failure.code} type=${failure.exceptionType}")
            return if (attempted) VirtualDisplayManualClose.Result("closed_unconfirmed", "RELEASE_UNCONFIRMED") else blocked(failure.code)
        } finally {
            if (borrowed) client?.close()
        }
    }

    /** Called only by the settings recovery button, not by automatic run cleanup. */
    @Synchronized fun recoverAndFinishManually(context: Context): JSONObject {
        val state = recoveryStatus(context)
        if (!state.optBoolean("ok")) return state
        if (!state.optBoolean("present")) return reply(false, "NO_VIRTUAL_SESSION")
        if (!state.optBoolean("recoverable")) return reply(false, "VIRTUAL_SESSION_BUSY")
        // A failed reconnect may leave only an in-memory placeholder. Keep the persisted
        // capability intact, but allow the next explicit attempt to authenticate it again.
        val previous = sessions.entries.singleOrNull { it.value.phase != "finished" }
        if (previous != null && previous.value.client == null) sessions.remove(previous.key)
        // 先只读核验 owner：确认它还在、状态可判定，才谈得上释放。
        val budgetBlocked = sessions.values.any { it.phase != "finished" && it.handoffBudget.blocked }
        when (VirtualDisplayManualRecovery.decide(probeRecoveredOwner(context), budgetBlocked)) {
            VirtualDisplayManualRecovery.Action.CLEAR_OWNER_GONE -> {
                // 副屏随 owner 进程一起消失，这里只清 App 侧记录，绝不补发释放。
                val cleared = runCatching { recoveryPrefs(context).edit().clear().commit() }.getOrDefault(false)
                sessions.clear()
                return if (cleared) reply(true).put("released", true).put("cleared", "OWNER_GONE")
                else reply(false, "RECOVERY_RECORD_CLEAR_FAILED").put("released", false)
            }
            VirtualDisplayManualRecovery.Action.REPORT_UNVERIFIED -> {
                val diag = runCatching {
                    recoveryPrefs(context).getString(VirtualDisplayRecoveryRecord.DIAG, "").orEmpty()
                }.getOrDefault("")
                return reply(false, "RECOVERY_RELEASED_UNVERIFIED", diag)
                    .put("released", false).put("manual_restart_required", true)
            }
            VirtualDisplayManualRecovery.Action.VERIFY_THEN_FINISH -> Unit
        }
        val runId = "manual-vd-recovery-" + java.util.UUID.randomUUID()
        try {
            val restored = start(context, runId, createIfMissing = false)
            if (!restored.optBoolean("ok")) return restored
            return finish(runId, context)
        } finally {
            // There is no Agent run to close this manual operation. Even failed start/finish
            // must leave recovery available instead of permanently reporting BUSY.
            sessions[runId]?.let { it.closedRun = true; it.cleanupOnly = true }
        }
    }

    @Synchronized fun keep(runId: String, args: JSONObject): JSONObject {
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.abortCleanupAttempted) return reply(false, "SESSION_NOT_ACTIVE")
        if (s.phase != "active" && !s.cleanupOnly) return reply(false, "SESSION_NOT_ACTIVE")
        val wanted = linkedSetOf<Int>()
        if (args.has("task_ids")) wanted.addAll(ids(args.optJSONArray("task_ids"))
            ?: return reply(false, "INVALID_TASK_IDS"))
        val packages = mutableListOf<String>()
        if (args.has("package_name")) packages.add(args.getString("package_name"))
        args.optJSONArray("packages")?.let { a -> for (i in 0 until a.length()) packages.add(a.getString(i)) }
        for (pkg in packages) wanted.addAll(s.packages[pkg] ?: return reply(false, "PACKAGE_NOT_SESSION_OWNED"))
        if (wanted.isEmpty()) return reply(false, "NO_DELIVERY_TASKS")
        val state = s.client?.status() ?: return reply(false, "RECOVERY_UNCERTAIN")
        if (!state.ok) return body(state)
        val data = body(state)
        val known = ids(data.optJSONArray("retainedTaskIds")) ?: return reply(false, "OWNER_TASKS_UNKNOWN")
        if (!known.containsAll(wanted)) return reply(false, "TASK_NOT_SESSION_OWNED")
        // A missing liveTaskIds field stays on the retained-only check. A present field must parse
        // and contain every selected id; gone or escaped ids are not live.
        if (data.has("liveTaskIds")) {
            val live = ids(data.optJSONArray("liveTaskIds")) ?: return reply(false, "OWNER_STATE_UNKNOWN")
            VirtualDisplayHandoffRetry.nonLiveKeepCode(live, wanted)?.let { return reply(false, it) }
        }
        val next = s.kept + wanted
        val context = recoveryContext ?: return reply(false, "RECOVERY_STATE_UNWRITABLE")
        if (!runCatching { recoveryPrefs(context).edit().putString("kept", JSONArray(next).toString()).commit() }.getOrDefault(false))
            return fail(s, "RECOVERY_STATE_UNWRITABLE")
        s.kept.addAll(wanted)
        return reply(true).put("kept_task_ids", JSONArray(s.kept))
    }
    /** Result finalization queries only this run; it must not adopt another conversation. */
    @Synchronized fun deliveryReceipt(runId: String): JSONObject? = sessions[runId]?.receipt
    @Synchronized fun holdOnCancelledRun(runId: String) {
        sessions[runId]?.let { s ->
            s.closedRun = true
            if (s.phase == "active") s.phase = "held"
        }
    }
    @Synchronized fun finish(runId: String, context: Context? = null): JSONObject {
        val ctx = context ?: recoveryContext
        if (sessions[runId] == null) {
            if (ctx == null) return reply(false, "NO_VIRTUAL_SESSION")
            val hasRecord = runCatching { recoveryPrefs(ctx).all.isNotEmpty() }.getOrDefault(true)
            val held = sessions.values.any { it.closedRun && it.phase != "finished" }
            if (!hasRecord && !held) return reply(false, "NO_VIRTUAL_SESSION")
            val recovered = start(ctx, runId, createIfMissing = false)
            if (!recovered.optBoolean("ok")) return recovered
        }
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.phase == "finished") return s.receipt?.let {
            if (it.opt("ok") == false) VirtualDisplayHandoffDiagnostics.preservedReceipt(it) else it
        } ?: reply(true).put("already_finished", true).put("released", true)
        // An ambiguous attempt must not reach either handoff OR release, even after adoption.
        if (s.handoffBudget.blocked || Thread.currentThread().isInterrupted)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val c = s.client ?: return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val observed = freshHandoffState(c) ?: return (clearWhenOwnerVerifiedGone(context, s)
            ?: failPreservingPrior(s, "OWNER_STATE_UNKNOWN"))
        // 释放副屏前必须先把接管过来的 task 搬回主屏：副屏一销毁，它们会跟着消失。
        returnTakenOver(c, s)?.let { return it }
        val marked = s.kept.toSet()
        val baseline = s.handoffState
        val f = observed.flags
        val action = VirtualDisplayRecoveryPolicy.finishAction(f)
        // A marked task that the system already removed cannot be delivered; deliver the rest.
        // Escaped (moved elsewhere / identity changed) marked tasks still fail closed below.
        val goneDelivery = if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF)
            observed.goneTaskIds?.let { marked.intersect(it) }.orEmpty() else emptySet()
        val frozen = marked - goneDelivery
        val directRelease = action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isEmpty() &&
            f.sourceEmpty && observed.retainedTaskIds.isEmpty()
        val cleanup = action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isEmpty() && !directRelease
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isNotEmpty()) {
            // Never drop an escaped id out of the selection to make delivery succeed.
            VirtualDisplayHandoffRetry.frozenDeliveryCode(
                observed.liveTaskIds, observed.goneTaskIds, observed.retainedTaskIds, frozen,
            )?.let { return reply(false, it) }
        }
        if (baseline != null) {
            val fresh = if (cleanup) VirtualDisplayHandoffRetry.freshStateAllowsCleanup(baseline, observed)
                else VirtualDisplayHandoffRetry.freshStateAllowsRetry(baseline, observed, frozen)
            if (s.handoffSelection != frozen || !fresh) return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        }
        if (action == VirtualDisplayRecoveryPolicy.Action.REFUSE)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val releaseOnly = action == VirtualDisplayRecoveryPolicy.Action.RELEASE_ONLY
        // A completed cleanup-only handoff (possibly from an earlier attempt or process) delivered
        // nothing. Unknown provenance never claims delivery.
        val priorCleanupOnly = releaseOnly &&
            runCatching { c.status().json?.opt("handoffCleanupOnly") }.getOrNull() != false
        var handedOff = releaseOnly && !priorCleanupOnly
        var cleanedUp = releaseOnly && priorCleanupOnly
        var removedForReceipt = emptySet<Int>()
        var goneForReceipt = emptySet<Int>()
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && (frozen.isNotEmpty() || cleanup)) {
            // Frozen selection: retries never widen it; compare with the pre-attempt baseline.
            // Cleanup uses an empty taskIds list and does not count as delivery.
            val before = baseline ?: observed
            val allowed = if (cleanup) VirtualDisplayHandoffRetry.freshStateAllowsCleanup(before, observed)
                else VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, observed, frozen)
            if (!allowed) return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
            if (!s.handoffBudget.hasRemaining()) {
                // Same round: still a FAILURE, never another handoff/release or a budget reset.
                // The status above is fresh; cached diagnostics alone cannot keep this pending.
                if (s.phase == "handoff_pending" && s.receipt?.opt("ok") == false)
                    return VirtualDisplayHandoffDiagnostics.preservedReceipt(s.receipt!!)
                return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
            }
            s.handoffState = before
            s.handoffSelection = frozen
            s.phase = "finishing"
            var acceptedRemoved: Set<Int>? = null
            var acceptedGone: Set<Int>? = null
            val outcome = VirtualDisplayHandoffRetry.run(
                budget = s.handoffBudget,
                handoff = {
                    val handoff = c.handoff(mapOf("taskIds" to if (cleanup) JSONArray() else JSONArray(frozen)))
                    val keptIds = ids(handoff.json?.optJSONArray("keptTaskIds"))
                    val removedIds = ids(handoff.json?.optJSONArray("removedTaskIds"))
                    val goneIds = ids(handoff.json?.optJSONArray("goneTaskIds"))
                    val completed = VirtualDisplayHandoffEvidence.completed(
                        authenticatedConnection = c.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        selectedIds = frozen,
                        retainedIds = before.retainedTaskIds,
                        keptIds = keptIds,
                        removedIds = removedIds,
                        field = { handoff.json?.opt(it) },
                        goneIds = goneIds,
                    )
                    recordHandoff(s, handoff, completed)
                    if (completed) {
                        acceptedRemoved = removedIds
                        acceptedGone = goneIds
                        VirtualDisplayHandoffRetry.Attempt.Completed
                    } else VirtualDisplayHandoffEvidence.refused(
                        authenticatedConnection = c.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        code = handoff.errorCode.ifBlank { "HANDOFF_UNCERTAIN" },
                        safeDetail = safeOwnerDetail(handoff),
                        field = { handoff.json?.opt(it) },
                    )
                },
                verifyFresh = {
                    val after = freshHandoffState(c)
                    if (cleanup) VirtualDisplayHandoffRetry.freshStateAllowsCleanup(before, after)
                    else VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, after, frozen)
                },
                delay = { millis -> Thread.sleep(millis) },
            )
            when (outcome) {
                VirtualDisplayHandoffRetry.Outcome.HandedOff -> {
                    if (cleanup) {
                        cleanedUp = true
                        removedForReceipt = acceptedRemoved.orEmpty()
                        goneForReceipt = acceptedGone.orEmpty()
                    } else handedOff = true
                }
                is VirtualDisplayHandoffRetry.Outcome.Stopped -> {
                    // Do not send a PROVEN read-only rejection through fail(), which marks
                    // ambiguity. Unknown/unauthenticated/post-anchor outcomes remain uncertain.
                    val failure = outcome.failure
                    s.phase = outcome.phase
                    val receipt = if (failure != null) reply(false,
                        VirtualDisplayHandoffDiagnostics.safeHandoffError(failure.code), failure.detail).withHandoffAttempts(s)
                        else s.receipt?.let(VirtualDisplayHandoffDiagnostics::preservedReceipt)
                            ?: reply(false, "HANDOFF_UNCERTAIN").withHandoffAttempts(s)
                    return VirtualDisplayHandoffDiagnostics.annotate(
                        receipt, s.handoffDiagnostics, uncertain = !outcome.cleanPreflight,
                    ).also { s.receipt = it }
                }
            }
        }
        // Re-read identity and phase before release; never replay an uncertain release or handoff.
        // Cleanup still finishes the owner handoff phase, but the receipt handedOff flag stays false.
        val beforeRelease = freshHandoffState(c)
        val latest = beforeRelease?.flags
        val handoffPhaseDone = cleanedUp || handedOff
        if (Thread.currentThread().isInterrupted || beforeRelease == null || latest == null ||
            beforeRelease.identity != observed.identity || beforeRelease.retainedTaskIds != observed.retainedTaskIds ||
            !latest.sourceEmpty || latest.releaseAttempted || latest.mutationUncertain ||
            latest.finishing != handoffPhaseDone || latest.handoffComplete != handoffPhaseDone)
            return fail(s, "RELEASE_UNCERTAIN")
        s.handoffBudget.stop() // No second release, even if its response is lost or malformed.
        // A successful release stops the owner; post-response isAlive is not transport proof.
        val authenticatedReleaseConnection = c.isAlive
        if (!authenticatedReleaseConnection) return fail(s, "RELEASE_UNCERTAIN")
        val releaseContext = ctx ?: return fail(s, "RECOVERY_STATE_UNWRITABLE")
        if (!runCatching { recoveryPrefs(releaseContext).edit()
                .putString(VirtualDisplayRecoveryRecord.BARRIER, "automatic_release_pending").commit() }.getOrDefault(false))
            return fail(s, "RECOVERY_STATE_UNWRITABLE")
        val released = try { c.release() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return fail(s, "RELEASE_UNCERTAIN") }
            catch (_: Exception) { return fail(s, "RELEASE_UNCERTAIN") }
        if (!VirtualDisplayHandoffEvidence.released(observed.identity, authenticatedReleaseConnection,
                released.ok && released.op == VirtualDisplayOwnerProtocol.OP_RELEASE, { released.json?.opt(it) }))
            return fail(s, released.errorCode.ifBlank { "RELEASE_UNCERTAIN" }, safeOwnerDetail(released))
        val receipt = clearReleased(ctx, s).put("handedOff", handedOff)
        if (goneDelivery.isNotEmpty()) receipt.put("goneDeliveryTaskIds", JSONArray(goneDelivery))
        if (cleanedUp) {
            receipt.put("cleanedUp", true)
                .put("removedTaskIds", JSONArray(removedForReceipt))
                .put("goneTaskIds", JSONArray(goneForReceipt))
        }
        return receipt.withHandoffAttempts(s).also { s.receipt = it }
    }
    /** Finalize only this failed/cancelled run, never adopt a different run's recovery owner. */
    @Synchronized fun onRunAborted(context: Context, runId: String): JSONObject? {
        val s = sessions[runId] ?: return null
        s.closedRun = true
        if (s.phase == "finished") return s.receipt
            ?: reply(false, "ABORT_CLEANUP_UNVERIFIED").put("released", false)
        if (s.abortCleanupAttempted) return s.receipt
            ?: reply(false, "ABORT_CLEANUP_UNVERIFIED").put("released", false)
        // start() can bind a held/recovered owner from another run to this runId. Do not
        // erase that owner's marks or remove its tasks merely because this later run failed.
        if (s.cleanupOnly) return reply(false, "RECOVERY_REQUIRED").put("released", false)
        s.abortCleanupAttempted = true
        // A failed/ambiguous previous handoff must not be replayed or get a different selection.
        if (s.handoffBudget.blocked || s.handoffState != null || s.handoffSelection != null)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        if (!s.persisted) return fail(s, "RECOVERY_STATE_UNWRITABLE")
        val c = s.client ?: return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val probe = probeHandoffState(c)
        val observed = probe.state ?: run {
            // 先确认 owner 是不是真的没了：还在就保留现场、绝不重放。
            clearWhenOwnerVerifiedGone(context, s)?.let { return it }
            // 读不出已知状态：把 owner 的原始字段摘要有界落盘，下次才不用猜。
            persistOwnerDiag(context, probe.response)
            return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        }
        val f = observed.flags
        if (f.finishing || f.handoffComplete || f.releaseAttempted || f.mutationUncertain)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        // Explicit keep is a delivery selection, not authorization to deliver an unfinished run.
        // Persist empty selection before cleanup so process recovery cannot re-promote it.
        val cleared = runCatching { recoveryPrefs(context).edit()
            .putString("kept", "[]").commit() }.getOrDefault(false)
        if (!cleared) return fail(s, "RECOVERY_STATE_UNWRITABLE")
        s.kept.clear()
        s.cleanupOnly = true
        s.phase = "held"
        return try { finish(runId, context) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(s, "AUTO_CLEANUP_INTERRUPTED")
        } catch (_: Exception) { fail(s, "AUTO_CLEANUP_FAILED") }
    }

    /** Called once when the owning run closes; explicit delivery choices are preserved. */
    @Synchronized fun onRunClosed(context: Context, runId: String) {
        val s = sessions[runId] ?: return
        s.closedRun = true
        if (s.phase == "finished") return
        if (s.kept.isEmpty() && !s.cleanupOnly) {
            // Only tasks this run launched and that status still shows as live are delivery tasks.
            // An unknown live inventory must not promote every package task.
            val live = s.client?.let { freshHandoffState(it)?.liveTaskIds }
            if (live != null) {
                val owned = s.packages.values.flatten().toSet()
                val promoted = owned.intersect(live)
                // Persist before use, like keep(): a restart must not turn this delivery into cleanup.
                if (promoted.isNotEmpty()) {
                    val persisted = runCatching { recoveryPrefs(context).edit()
                        .putString("kept", JSONArray(promoted).toString()).commit() }.getOrDefault(false)
                    if (!persisted) {
                        s.receipt = reply(false, "RECOVERY_STATE_UNWRITABLE")
                        AndroidAgentLogger.warn("Virtual display auto-finish failed; recovery retained")
                        return
                    }
                    s.kept.addAll(promoted)
                }
            }
        }
        if (s.client == null) {
            failPreservingPrior(s, "RECOVERY_UNCERTAIN")
            return
        }
        s.receipt = runCatching { finish(runId, context) }.getOrElse { fail(s, "AUTO_FINISH_FAILED") }
        if (!s.receipt!!.optBoolean("ok")) AndroidAgentLogger.warn("Virtual display auto-finish failed; recovery retained")
    }
    @Synchronized fun executeGui(context: Context,runId: String,tool: String,args: JSONObject, excludedPackages: Set<String> = emptySet()): AgentModelClient.ToolResult {
        fun text(obj: JSONObject)=AgentModelClient.ToolResult(obj.put("tool",tool).put("display","virtual").toString())
        if(tool !in setOf("observe_screen","launch_app","wait","tap","tap_area","swipe","long_press","press_key","paste_text","input_text","replace_text","clear_text","type_text","wait_for_text","wait_for_package","tap_element","long_press_element","scroll","scroll_element"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
        if(tool=="press_key" && args.optString("button") !in setOf("BACK","ENTER","PASTE"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
        if(tool=="launch_app" && (args.optString("package_name").isBlank() || args.optString("package_name") in excludedPackages))return text(reply(false,"PACKAGE_NOT_ALLOWED"))
        if(sessions[runId]==null && tool !in setOf("launch_app","observe_screen"))return text(reply(false,"NO_VIRTUAL_SESSION"))
        if(sessions[runId]==null){val created=start(context,runId);if(!created.optBoolean("ok"))return text(created)}
        val s=sessions[runId]?:return text(reply(false,"NO_VIRTUAL_SESSION"))
        s.lastActivityMs = System.currentTimeMillis()
        ensureIdleMonitor(context)
        s.previewExcludedPackages.addAll(excludedPackages)
        if(s.phase!="active" || s.cleanupOnly || !s.persisted)return text(reply(false,"SESSION_NOT_ACTIVE"))
        val c=s.client!!
        try {
            if(tool=="observe_screen") {
                //观察失败不得保留上一帧的有效坐标：先作废，成功登记新帧后才能再次输入。
                s.observation.invalidate()
                val visible=c.status()
                val packages=body(visible).optJSONArray("sourcePackages")
                if(!visible.ok || packages==null)return text(reply(false,"SCREEN_CONTENT_UNKNOWN"))
                if((0 until packages.length()).any { packages.getString(it) in excludedPackages })return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                val shot=c.snapshot();if(!shot.ok)return text(body(shot))
                val data=body(shot);val encoded=data.optString("data")
                val frameWidth=data.optInt("width",0);val frameHeight=data.optInt("height",0)
                if(encoded.isBlank() || frameWidth<=0 || frameHeight<=0)return text(reply(false,"NO_FRAME"))
                val capture=try {
                    VirtualDisplayScreenCapture.capture(encoded,frameWidth,frameHeight)
                } catch(e:Exception) {
                    return text(reply(false,"FRAME_ENCODE_FAILED",e.javaClass.simpleName))
                }
                s.observation.record(capture.contract)
                // 副屏节点来自 App 自身的无障碍服务（按 display 取树）；取不到时保持纯截图模式，坐标契约不变。
                val vdNodeSnapshot = captureVirtualNodes(context, s, NODE_LIMIT, c.displayId)
                val elementObservation = vdNodeSnapshot?.let { snapshot ->
                    RootShellDeviceController.ElementObservation(
                        id = snapshot.id,
                        source = RootShellDeviceController.ElementSource.ACCESSIBILITY,
                        packageName = snapshot.packageName,
                        windowId = snapshot.windowId,
                        nodes = DeviceNodeProjection.project(snapshot.nodes),
                        maxNodes = NODE_LIMIT,
                        truncated = snapshot.truncated,
                    )
                }
                s.uiTreeAvailability.updateScope(runId, c.displayId, elementObservation?.packageName.orEmpty())
                val nodesUnavailable = s.uiTreeAvailability.record(elementObservation?.nodes.orEmpty().isNotEmpty())
                if (nodesUnavailable) {
                    s.observation.recordNodes(null, null)
                } else {
                    s.observation.recordNodes(elementObservation, vdNodeSnapshot)
                }
                data.remove("data");data.remove("width");data.remove("height");data.remove("bytes");data.remove("format")
                return AgentModelClient.ToolResult(
                    data.put("tool",tool)
                        .put("screen",JSONObject().put("width",capture.contract.screenWidth).put("height",capture.contract.screenHeight))
                        .put("screenshot",JSONObject().put("width",capture.contract.screenshotWidth)
                            .put("height",capture.contract.screenshotHeight)
                            .put("mime_type",capture.image.mimeType)
                            .put("bytes",capture.image.bytes))
                        .put("coordinate_contract",capture.contract.toContractJson())
                        .put("observation_id", elementObservation?.id ?: JSONObject.NULL)
                        .put("observation_source", elementObservation?.source?.wireName ?: JSONObject.NULL)
                        .put("node_limit", NODE_LIMIT)
                        .put("ui_tree_unavailable", nodesUnavailable)
                        .put("ui_tree_truncated", elementObservation?.truncated ?: false)
                        .put("ui_nodes", elementObservation?.let { DeviceNodeProjection.json(it.nodes) } ?: JSONArray())
                        .put(
                            "note",
                            if (elementObservation != null && elementObservation.nodes.isNotEmpty()) {
                                "虚拟屏节点来自无障碍服务（display ${c.displayId}）；坐标为截图空间映射到副屏像素，screen 空间直通，normalized 为 0..999 相对坐标"
                            } else {
                                "本次未取到副屏节点（应用窗口可能未就绪或无可访问子树）；仍可用截图+坐标操作，坐标为截图空间映射到副屏像素"
                            }
                        )
                        .toString(),
                    listOf(capture.image))
            }
            if(tool=="launch_app") {
                val pkg=args.optString("package_name")
                if(pkg in excludedPackages)return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                if(pkg.isBlank())return text(reply(false,"PACKAGE_NAME_REQUIRED","先 search_apps 获取精确包名"))
                val component=context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
                    ?:return text(reply(false,"APP_NOT_LAUNCHABLE"))
                val before=c.status();if(!before.ok)return text(body(before))
                val previous=body(before).optJSONArray("retainedTaskIds")?:JSONArray()
                val prior=(0 until previous.length()).map { previous.getInt(it) }.toSet()
                s.observation.invalidate()
                val launched=c.launch(component)
                if(!launched.ok) {
                    // 主屏同应用占用：首选接管（把那个 task 搬过来），否则把选择权交给用户；本次未执行、未停止任何应用。
                    val conflictCode=launched.errorCode.ifBlank { body(launched).optString("error") }
                    if(conflictCode in VirtualDisplayLaunchConflict.codes) {
                        val autoTakeover=conflictTakeoverPreferred(context)
                        if(args.optBoolean("takeover",false) || autoTakeover) {
                            val taken=c.takeover(pkg)
                            if(taken.ok) {
                                val takenBody=body(taken)
                                val takenId=takenBody.optInt("taskId",0)
                                if(takenId>0) s.takenOver[pkg]=takenId
                                // 画面已换成被接管的那个应用：坐标必须重新观察。
                                s.observation.invalidate()
                                s.recordTrace("launch_app","takeover",pkg,true)
                                return text(JSONObject()
                                    .put("ok",true)
                                    .put("launched",true)
                                    .put("taken_over",true)
                                    .put("package_name",pkg)
                                    .put("taskId",takenId)
                                    .put("displayId",c.displayId)
                                    .put("note","已把主屏那个实例搬到副屏接管（未杀进程、未重置界面）；" +
                                        "任务收尾时会自动还回主屏。先用 observe_screen 取新界面。"))
                            }
                            // 接管失败：如实回报并退回原来的选项，绝不悄悄改成强停。
                            return text(VirtualDisplayLaunchConflict.payload(pkg,conflictCode,body(launched).optString("message"),autoTakeover)
                                .put("takeover_error",taken.errorCode)
                                .put("message","接管失败（${taken.errorCode}），本次未执行；请选择下面的处理方式。"))
                        }
                        return text(VirtualDisplayLaunchConflict.payload(pkg,conflictCode,body(launched).optString("message"),autoTakeover))
                    }
                }
                if(launched.ok) {
                    val payload=body(launched)
                    // reused=true switches back to this session's existing task. current-prior is
                    // empty, so do not replace the package's recorded task ids with that diff.
                    if(payload.opt("reused")!=true) {
                        val ids=payload.optJSONArray("taskIds")?:return text(reply(false,"LAUNCH_TASKS_UNKNOWN"))
                        val current=(0 until ids.length()).map{ids.getInt(it)}.toSet()
                        s.packages[pkg]=(s.packages[pkg]?:emptySet())+(current-prior)
                    }
                    //新任务意味着画面已变，必须重新观察后才能用坐标。
                    s.observation.invalidate()
                }
                return text(body(launched))
            }
            if(tool=="wait") {Thread.sleep(args.optLong("duration_ms",1000).coerceIn(100,30000));return text(reply(true))}
            if (tool in setOf("replace_text","clear_text","input_text","paste_text","type_text")) {
                val value = if (tool == "clear_text") "" else args.optString("text")
                // paste_text 仍保留长文本通道；其余文本工具与主屏一致，上限 4000 字符。
                val textLimit = if (tool == "paste_text") 20_000 else MAX_TEXT_CHARS
                if (value.length > textLimit) return text(reply(false,"TEXT_TOO_LONG","最多支持 $textLimit 个字符"))
                val mode = when {
                    tool == "replace_text" || tool == "clear_text" -> "replace"
                    tool == "type_text" -> args.optString("mode","replace").takeIf { it == "append" } ?: "replace"
                    else -> "append"
                }
                val index = if (args.has("index")) args.optInt("index",-1).takeIf { it >= 0 } else null
                // submit 时把动作后回读推迟到回车之后：写入与回车之间只取一次节点，省一次 60 节点取树与 350ms 等待。
                val submit = tool == "type_text" && args.optBoolean("submit",false)
                val payload = writeVirtualText(context, s, c, c.displayId, mode, index, value, deferAfterAction = submit)
                if (!payload.optBoolean("ok")) return text(payload)
                if (submit) {
                    val enter = c.input(mapOf("kind" to "key","keyCode" to 66))
                    payload.put("submit",enter.ok)
                    if (!enter.ok) payload.put("submit_error",enter.errorCode)
                    // 回车成功与否都只在这里回读一次：写入路径已按 deferAfterAction 跳过自己的回读。
                    Thread.sleep(AFTER_ACTION_SETTLE_MS)
                    afterActionSummary(context, s, c.displayId, s.observation.publishedObservation())
                        ?.let { payload.put("after_action",it) }
                }
                return text(payload)
            }
            if (tool == "wait_for_text" || tool == "wait_for_package") {
                return text(waitOnVirtualDisplay(context, s, c, tool, args))
            }
            if (tool in setOf("tap", "tap_area", "swipe", "long_press", "tap_element", "long_press_element", "scroll", "scroll_element")) {
                s.observation.require()
                val live = c.status()
                if (!live.ok) {
                    s.observation.invalidate()
                    return text(reply(false,"VIRTUAL_FRAME_UNKNOWN"))
                }
                s.observation.validateFrame(body(live).optInt("width", 0), body(live).optInt("height", 0))
            }
            val fields=linkedMapOf<String,Any?>()
            val space=args.optString("coordinate_space").takeIf { it.isNotBlank() }
            when(tool) {
                "tap","tap_area" -> {
                    val point=if(tool=="tap") {
                        s.observation.resolve(space,args.getInt("x"),args.getInt("y"))
                    } else {
                        //tap_area：两个端点都必须有效，中心取映射后两端点的中点。
                        s.observation.resolveArea(space,args.getInt("x1"),args.getInt("y1"),args.getInt("x2"),args.getInt("y2"))
                    }
                    fields.putAll(mapOf("kind" to "tap","x" to point.x,"y" to point.y))
                }
                "swipe","long_press" -> {
                    val x1Key=if(tool=="swipe")"x1" else "x";val y1Key=if(tool=="swipe")"y1" else "y"
                    val first=s.observation.resolve(space,args.getInt(x1Key),args.getInt(y1Key))
                    val second=if(tool=="swipe") s.observation.resolve(space,args.getInt("x2"),args.getInt("y2")) else first
                    fields.putAll(mapOf("kind" to "swipe","x1" to first.x,"y1" to first.y,"x2" to second.x,"y2" to second.y,"durationMs" to args.optInt("duration_ms",500).coerceIn(100,3000)))
                }
                "press_key" -> {
                    val key=mapOf("BACK" to 4,"ENTER" to 66,"PASTE" to 279)[args.getString("button")]
                        ?:return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY"))
                    fields.putAll(mapOf("kind" to "key","keyCode" to key))
                }
                "tap_element","long_press_element" -> {
                    if (s.uiTreeAvailability.unavailable()) return text(reply(false,"VIRTUAL_NODES_UNAVAILABLE","该应用窗口暂无可访问节点，请用截图+坐标操作"))
                    val index=args.getInt("index")
                    val node=s.observation.node(index)
                        ?: return text(reply(false,"VIRTUAL_NODE_INDEX_UNKNOWN","先 observe_screen 取当前节点索引"))
                    val point=s.observation.resolve("screen",node.centerX,node.centerY)
                    if(tool=="tap_element") {
                        fields.putAll(mapOf("kind" to "tap","x" to point.x,"y" to point.y))
                    } else {
                        fields.putAll(mapOf("kind" to "swipe","x1" to point.x,"y1" to point.y,"x2" to point.x,"y2" to point.y,
                            "durationMs" to args.optInt("duration_ms",800).coerceIn(300,3000)))
                    }
                }
                "scroll","scroll_element" -> {
                    if (s.uiTreeAvailability.unavailable()) return text(reply(false,"VIRTUAL_NODES_UNAVAILABLE","该应用窗口暂无可访问节点，请用截图+坐标操作"))
                    val direction=args.optString("direction")
                    if(direction !in setOf("up","down","left","right"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY","direction 必须是 up/down/left/right"))
                    val amount=args.optString("amount","medium")
                    if(amount !in setOf("small","medium","large","page"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY","amount 必须是 small/medium/large/page"))
                    val node=if(tool=="scroll_element") {
                        s.observation.node(args.getInt("index"))
                            ?: return text(reply(false,"VIRTUAL_NODE_INDEX_UNKNOWN","先 observe_screen 取当前节点索引"))
                    } else {
                        null
                    }
                    val contract=s.observation.require()
                    val area=node?.bounds ?: android.graphics.Rect(0,0,contract.screenWidth,contract.screenHeight)
                    val cx=area.centerX();val cy=area.centerY()
                    val horizontal=direction=="left"||direction=="right"
                    val fraction=when(amount){"small"->0.3f;"large"->0.85f;"page"->0.95f;else->0.6f}
                    val span=((if(horizontal) area.width() else area.height())*fraction).toInt().coerceAtLeast(60)
                    val half=span/2
                    val (sx,sy,ex,ey) = when(direction) {
                        "down" -> listOf(cx,cy+half,cx,cy-half)
                        "up" -> listOf(cx,cy-half,cx,cy+half)
                        "right" -> listOf(cx-half,cy,cx+half,cy)
                        else -> listOf(cx+half,cy,cx-half,cy)
                    }
                    val first=s.observation.resolve("screen",sx,sy)
                    val second=s.observation.resolve("screen",ex,ey)
                    fields.putAll(mapOf("kind" to "swipe","x1" to first.x,"y1" to first.y,"x2" to second.x,"y2" to second.y,
                        "durationMs" to args.optInt("duration_ms",400).coerceIn(100,3000)))
                }
                else -> return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY","不会回退到主屏操作"))
            }
            // 动作后回读：先留一份动作前节点，成功动作才附带新界面（只取节点，不取截图）。
            val beforeAction = s.observation.publishedObservation()
            val input = c.input(fields)
            if (input.errorCode in setOf("VIRTUAL_FRAME_CHANGED", "VIRTUAL_FRAME_UNKNOWN")) s.observation.invalidate()
            val result = body(input)
            s.recordTrace(tool, fields["kind"]?.toString().orEmpty(), fields.toString(), input.ok)
            if (input.ok) {
                Thread.sleep(AFTER_ACTION_SETTLE_MS)
                afterActionSummary(context, s, c.displayId, beforeAction)?.let { result.put("after_action", it) }
            }
            return text(result)
        }catch(e:VirtualDisplayCoordinateRejection){return text(reply(false,e.code,e.message.orEmpty()))}
        catch(e:Exception){return text(reply(false,"VIRTUAL_OPERATION_FAILED",e.javaClass.simpleName))}
    }
    private fun Session.recordTrace(tool: String, kind: String, detail: String, ok: Boolean) {
        recentActions.addLast(
            JSONObject()
                .put("tool", tool)
                .put("kind", kind)
                .put("ok", ok)
                .put("detail", detail.take(160))
                .put("at", System.currentTimeMillis())
                .toString(),
        )
        while (recentActions.size > TRACE_LIMIT) recentActions.removeFirst()
    }

    /** 空闲巡检只在「run 已结束但副屏还留着」时收尾，绝不动正在运行的任务。 */
    private fun ensureIdleMonitor(context: Context) {
        if (idleMonitor != null) return
        synchronized(this) {
            if (idleMonitor != null) return
            val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "eta-vd-idle").apply { isDaemon = true }
            }
            executor.scheduleWithFixedDelay(
                {
                    runCatching { checkIdleSessions() }
                        .onFailure { AndroidAgentLogger.warn("Virtual display idle check failed: ${it.javaClass.simpleName}") }
                },
                IDLE_CHECK_INTERVAL_MS,
                IDLE_CHECK_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            idleMonitor = executor
        }
    }

    private fun checkIdleSessions() {
        val context = recoveryContext ?: return
        val timeoutMs = idleTimeoutMs(context)
        if (timeoutMs <= 0L) return
        val now = System.currentTimeMillis()
        val expired = sessions.entries
            .filter { (_, s) -> s.phase != "finished" && s.closedRun && now - s.lastActivityMs > timeoutMs }
            .map { it.key }
            .toList()
        for (runId in expired) {
            AndroidAgentLogger.info("Virtual display idle timeout reached; finishing run=$runId")
            runCatching { finish(runId, context) }
        }
    }

    /** 悬浮小窗开关（与设置页共用同一偏好）。 */
    private fun floatingWindowEnabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(AGENT_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(FLOATING_WINDOW_PREF, false)
    }.getOrDefault(false)

    private fun idleTimeoutMs(context: Context): Long {
        val minutes = runCatching {
            context.getSharedPreferences(AGENT_PREFERENCES, Context.MODE_PRIVATE)
                .getInt(IDLE_TIMEOUT_PREF, DEFAULT_IDLE_TIMEOUT_MINUTES)
        }.getOrDefault(DEFAULT_IDLE_TIMEOUT_MINUTES)
        return if (minutes <= 0) 0L else minutes * 60_000L
    }

    /** 只读镜像页的状态快照：只给运行态与计数，不给节点、截图或包内容。 */
    @Synchronized fun viewerStatus(): JSONObject {
        val session = sessions.entries.firstOrNull { it.value.phase != "finished" }?.value
        return JSONObject()
            .put("running", session != null)
            .put("display_id", session?.client?.displayId ?: JSONObject.NULL)
            .put("phase", session?.phase ?: "")
            .put("run_closed", session?.closedRun ?: true)
            .put("delivered_tasks", session?.kept?.size ?: 0)
            .put(
                "idle_seconds",
                if (session != null) (System.currentTimeMillis() - session.lastActivityMs) / 1000 else JSONObject.NULL,
            )
    }

    /**
     * 只读镜像页取帧：复用本会话的 owner 连接取一帧，返回 {data(base64), width, height}。
     * 只读、不改变任何观察状态；没有会话或取帧失败时返回 null。
     */
    @Synchronized fun viewerFrame(): JSONObject? {
        val session = sessions.entries.firstOrNull { it.value.phase != "finished" }?.value ?: return null
        val client = session.client ?: return null
        val shot = runCatching { client.snapshot() }.getOrNull() ?: return null
        if (!shot.ok) return null
        val data = body(shot)
        val encoded = data.optString("data")
        val width = data.optInt("width", 0)
        val height = data.optInt("height", 0)
        if (encoded.isBlank() || width <= 0 || height <= 0) return null
        return JSONObject().put("data", encoded).put("width", width).put("height", height)
    }

    /** 只读镜像页的最近操作轨迹。 */
    @Synchronized fun recentActions(): JSONArray {
        val session = sessions.entries.firstOrNull { it.value.phase != "finished" }?.value ?: return JSONArray()
        return JSONArray().also { array -> session.recentActions.forEach { array.put(it) } }
    }

    /**
     * 副屏文本写入的唯一入口。
     *
     * 优先无障碍 SET_TEXT：完全不碰系统剪贴板，append 模式按节点当前文本追加。
     * 只有取不到输入节点时才回退系统剪贴板 + PASTE 键，并在动作结束后尽量还原原剪贴板内容
     * （读不到原内容时宁可不还原，也不清空用户的剪贴板）。
     */
    private fun writeVirtualText(
        context: Context,
        s: Session,
        c: VirtualDisplayOwnerClient,
        displayId: Int,
        mode: String,
        index: Int?,
        value: String,
        deferAfterAction: Boolean = false,
    ): JSONObject {
        val nodes = s.observation.publishedNodes()
        val target = if (index != null) nodes.firstOrNull { it.index == index } else VirtualDisplayTextTarget.pick(nodes)
        if (index != null && target == null) return reply(false,"VIRTUAL_NODE_INDEX_UNKNOWN","先 observe_screen 取当前节点索引")
        if (index != null && target != null && !target.editable) return reply(false,"NOT_EDITABLE","指定节点不可编辑")
        val snapshot = s.observation.nodesSnapshot()
        val service = if (snapshot != null) AgentAccessibilityService.current() else null
        if (target != null && snapshot != null && service != null) {
            val next = if (mode == "append") target.text + value else value
            val before = s.observation.publishedObservation()
            val outcome = service.setTextNode(snapshot, target.index, next)
            if (!outcome.ok) {
                return reply(false,outcome.code.ifBlank { "TEXT_SET_FAILED" },
                    outcome.message + "；可先 observe_screen 取新索引，或改用 tap_element 聚焦后 paste_text")
            }
            Thread.sleep(AFTER_ACTION_SETTLE_MS)
            val payload = JSONObject()
                .put("ok",true)
                .put("executor","accessibility")
                .put("index",target.index)
                .put("package_name",target.packageName)
                .put("mode",mode)
                .put("text_length",value.length)
                .put("clipboard_untouched",true)
            outcome.method.takeIf { it.isNotBlank() }?.let { payload.put("method",it) }
            outcome.verified?.let { payload.put("verified",it) }
            if (!deferAfterAction) afterActionSummary(context, s, displayId, before)?.let { payload.put("after_action",it) }
            return payload
        }
        if (nodes.isEmpty() && snapshot == null) {
            return reply(false,"VIRTUAL_NODES_UNAVAILABLE","副屏本次观察没有节点，请先 observe_screen 取节点")
        }
        // 回退：借系统剪贴板 + PASTE 键；先备份，动作后由 restoreVirtualClipboard 尽量还原。
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val backup = readClipboardText(context, clipboard)
        val hadClip = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)
        if (backup != value || !hadClip) {
            s.clipboardBackup = backup
            s.clipboardBackupPresent = hadClip
            s.clipboardWritten = value
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", value)) }
        }
        val before = s.observation.publishedObservation()
        val pasted = c.input(mapOf("kind" to "key","keyCode" to 279))
        if (!pasted.ok) return body(pasted)
        Thread.sleep(AFTER_ACTION_SETTLE_MS)
        val restored = restoreVirtualClipboard(context, s)
        val payload = JSONObject()
            .put("ok",true)
            .put("executor","clipboard")
            .put("mode",mode)
            .put("text_length",value.length)
            .put("clipboard_restored",restored)
            .put("note","副屏取不到输入节点，改用系统剪贴板 + PASTE 键输入；已尽量还原原剪贴板内容")
        if (!deferAfterAction) afterActionSummary(context, s, displayId, before)?.let { payload.put("after_action",it) }
        return payload
    }

    private fun readClipboardText(context: Context, clipboard: ClipboardManager): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    /** 用完还原：只有当前剪贴板仍是我们写入的值、且确实读到过原内容时才写回。 */
    private fun restoreVirtualClipboard(context: Context, s: Session): Boolean {
        val written = s.clipboardWritten
        val backup = s.clipboardBackup
        val hadBackup = s.clipboardBackupPresent
        s.clipboardWritten = null
        s.clipboardBackup = null
        s.clipboardBackupPresent = false
        if (backup == null || !hadBackup) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = readClipboardText(context, clipboard)
        if (VirtualClipboardRestore.restoreAction(written, current, backup, hadBackup) !=
            VirtualClipboardRestore.RestoreAction.WRITE_BACK) return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("", backup))
        }.isSuccess
    }

    /**
     * 副屏取树的唯一入口。
     *
     * 取不到节点时，只有在「连默认屏窗口都枚举不出来」的情况下才判定无障碍服务失效
     * （重装 APK 后常见：已启用、已绑定、实例也在，但窗口缓存为空），按会话最多做一次强制自愈
     * 后重试一次；应用窗口单纯未就绪时不重绑。自愈失败不改调用方语义，仍按纯截图路径继续。
     */
    private fun captureVirtualNodes(
        context: Context,
        s: Session,
        maxNodes: Int,
        displayId: Int,
    ): AgentAccessibilityService.NodeSnapshot? {
        val first = runCatching {
            AgentAccessibilityService.current()?.captureNodeSnapshot(maxNodes, displayId)
        }.getOrNull()
        if (first != null) return first
        if (s.accessibilityRecoveryAttempted || AgentAccessibilityService.defaultDisplayWindowsUsable()) return null
        s.accessibilityRecoveryAttempted = true
        val healed = runCatching {
            AgentAccessibilityKeeper.forceRecoveryForGuiOperation(context)
        }.getOrNull()
        AndroidAgentLogger.warn(
            "Virtual display node capture failed; accessibility force recovery " +
                "available=${healed?.available} code=${healed?.code}",
        )
        return runCatching {
            AgentAccessibilityService.current()?.captureNodeSnapshot(maxNodes, displayId)
        }.getOrNull()
    }

    /**
     * 动作后回读：只取节点，不取截图；取不到就返回 null，绝不改变动作本身的结果。
     * 同时把新节点发布给本次会话，模型可直接用新索引继续，不必再 observe_screen。
     */
    private fun afterActionSummary(
        context: Context,
        s: Session,
        displayId: Int,
        before: RootShellDeviceController.ElementObservation?,
    ): JSONObject? {
        if (s.uiTreeAvailability.unavailable()) return null
        val snapshot = captureVirtualNodes(context, s, AFTER_ACTION_NODE_LIMIT, displayId) ?: return null
        val after = RootShellDeviceController.ElementObservation(
            id = snapshot.id,
            source = RootShellDeviceController.ElementSource.ACCESSIBILITY,
            packageName = snapshot.packageName,
            windowId = snapshot.windowId,
            nodes = DeviceNodeProjection.project(snapshot.nodes),
            maxNodes = AFTER_ACTION_NODE_LIMIT,
            truncated = snapshot.truncated,
        )
        val summary = runCatching { AgentAfterActionSummary.build(before, after) }.getOrNull() ?: return null
        summary.put("coordinate_space_hint","ui_nodes 的 center 是副屏像素坐标；坐标操作请显式传 coordinate_space=screen")
        if (after.nodes.isNotEmpty()) s.observation.recordNodes(after, snapshot)
        return summary
    }

    /**
     * 副屏等待类工具：只在副屏取树轮询，绝不回退主屏。
     * 语义与主屏 wait_for_text / wait_for_package 对齐（匹配模式、attempts、超时错误码）。
     */
    private fun waitOnVirtualDisplay(
        context: Context,
        s: Session,
        c: VirtualDisplayOwnerClient,
        tool: String,
        args: JSONObject,
    ): JSONObject {
        val timeout = args.optInt("timeout_ms",10_000).coerceIn(500,60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        fun snapshot(): AgentAccessibilityService.NodeSnapshot? =
            captureVirtualNodes(context, s, NODE_LIMIT, c.displayId)
        if (tool == "wait_for_package") {
            val target = args.optString("package_name").trim()
            if (target.isBlank()) return reply(false,"INVALID_ARGUMENT","package_name 不能为空")
            var lastPackage = ""
            while (System.currentTimeMillis() <= deadline) {
                attempts++
                lastPackage = snapshot()?.packageName.orEmpty()
                if (lastPackage == target) {
                    return JSONObject().put("ok",true).put("package_name",target).put("attempts",attempts)
                }
                Thread.sleep(350)
            }
            return reply(false,"TIMEOUT","等待应用超时：$target")
                .put("attempts",attempts)
                .put("last_package",lastPackage)
        }
        val needle = args.optString("text").trim()
        if (needle.isBlank()) return reply(false,"INVALID_ARGUMENT","text 不能为空")
        val includeDesc = args.optBoolean("include_desc",true)
        val matchMode = args.optString("match","contains")
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val nodes = DeviceNodeProjection.project(snapshot()?.nodes.orEmpty())
            val match = nodes.firstOrNull { node ->
                val haystacks = if (includeDesc) listOf(node.text,node.desc) else listOf(node.text)
                haystacks.any { AgentTextMatcher.matches(it,needle,matchMode) }
            }
            if (match != null) {
                val matched = DeviceNodeProjection.nodeJson(match)
                matched.remove("index")
                matched.put("actionable",false)
                return JSONObject()
                    .put("ok",true)
                    .put("attempts",attempts)
                    .put("matched_node",matched)
                    .put("note","等待查询不会发布元素快照；如需节点动作，请重新调用 observe_screen")
            }
            Thread.sleep(350)
        }
        return reply(false,"TIMEOUT","等待文本超时：$needle").put("attempts",attempts)
    }

    fun onRunStarted(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun onRunFinished(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun engage(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun keep(packageName: String): Nothing = throw VirtualDisplayHandoffNotReadyException()
}
internal class VirtualDisplayHandoffNotReadyException : IllegalStateException(VirtualDisplaySession.NOT_READY)
