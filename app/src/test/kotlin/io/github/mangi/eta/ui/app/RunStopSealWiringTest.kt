package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 AgentAppState 停止解锁接线的不变量。
 * 这里直接核验源码接线；实际状态交互另由 AgentStopInteractionTest 在 Robolectric 验证。
 */
class RunStopSealWiringTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt"),
            File("app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("AgentAppState.kt 未找到：${candidates.map { it.absolutePath }}", file != null)
        file!!.readText()
    }

    @Test
    fun `stopRun arms the watchdog right after the stopping flag is published`() {
        val index = source.indexOf("stoppingRuns[runId] = retrying")
        assertTrue("未找到停止标志赋值", index >= 0)
        assertTrue(
            "停止后必须立即启动看门狗",
            source.substring(index, index + 200).contains("armStopSealWatchdog(runId)"),
        )
    }

    @Test
    fun `terminal events are no longer dropped while stopping`() {
        assertTrue(
            "停止窗口内必须放行终态事件",
            source.contains("if (RunStopEventGate.isRunTerminal(event)) {"),
        )
        assertFalse(
            "旧的单行终态丢弃条件必须已被替换",
            source.contains("if (stoppingRuns.containsKey(runId) && event !is AgentEvent.ContextCompacted &&"),
        )
    }

    @Test
    fun `the watchdog never consumes the stoppingRuns entry itself`() {
        val start = source.indexOf("private fun armStopSealWatchdog(")
        assertTrue("未找到看门狗实现", start >= 0)
        val end = source.indexOf("private fun cancelStopSealWatchdog(", start)
        assertTrue("未找到看门狗结尾", end > start)
        val body = source.substring(start, end)
        assertTrue(
            "看门狗只能检查停止标志，移除必须留给 applyRunResult",
            body.contains("if (!stoppingRuns.containsKey(runId)) return@withContext"),
        )
        assertFalse(
            "看门狗不得移除 stoppingRuns，否则 applyRunResult 会丢掉重试标志",
            body.contains("stoppingRuns.remove(runId)"),
        )
        assertTrue("超时必须补一条终态结果", body.contains("applyRunResult("))
    }

    @Test
    fun `finishStopSeal only shortens the grace period`() {
        val start = source.indexOf("private fun finishStopSeal(")
        assertTrue("未找到 finishStopSeal", start >= 0)
        val end = source.indexOf("private fun AgentEvent.allowedAfterSeal", start)
        assertTrue("未找到 finishStopSeal 的后续方法", end > start)
        val body = source.substring(start, end)
        assertTrue(
            "终态事件后改用短宽限看门狗",
            body.contains("armStopSealWatchdog(runId, stopSealTerminalTimeout)"),
        )
        assertFalse(
            "finishStopSeal 不得自行移除 stoppingRuns",
            body.contains("stoppingRuns.remove(runId)"),
        )
    }

    @Test
    fun `duplicate terminal events do not disarm the terminal watchdog`() {
        val start = source.indexOf("private fun finishStopSeal(")
        val end = source.indexOf("private fun AgentEvent.allowedAfterSeal", start)
        val body = source.substring(start, end)
        val duplicate = body.indexOf("if (stopSealTerminalTimeout.isPending(runId)) return")
        val cancel = body.indexOf("stopSealWatchdogJobs.remove(runId)?.cancel()")
        assertTrue("重复终态必须在撤看门狗之前返回", duplicate >= 0 && duplicate < cancel)
    }

    @Test
    fun `stale watchdog cannot remove the current watchdog handle`() {
        val start = source.indexOf("private fun armStopSealWatchdog(")
        val end = source.indexOf("private fun cancelStopSealWatchdog(", start)
        val body = source.substring(start, end).substringAfter("withContext(Dispatchers.Main.immediate)")
        val claim = body.indexOf("if (!timeout.claimUnlock(ticket)) return@withContext")
        val remove = body.indexOf("stopSealWatchdogJobs.remove(runId)")
        assertTrue("过期回调必须先核验票据再清理句柄", claim >= 0 && claim < remove)
    }

    @Test
    fun `applyRunResult and a rejected stop both cancel the watchdog`() {
        val callSites = Regex("^\\s+cancelStopSealWatchdog\\(runId\\)$", RegexOption.MULTILINE)
            .findAll(source)
            .count()
        assertTrue("撤看门狗的调用点至少要覆盖结果落定与停止被拒两处，实际 $callSites", callSites >= 2)
    }

    @Test
    fun `the terminal grace period is shorter than the full stop timeout`() {
        assertTrue(RunStopSealTimeout.TERMINAL_GRACE_MS < RunStopSealTimeout.DEFAULT_TIMEOUT_MS)
        assertTrue(RunStopSealTimeout.TERMINAL_GRACE_MS > 0L)
    }

    @Test
    fun `stop notices never claim the app is writing the transcript`() {
        assertFalse(
            "误导文案必须已移除",
            source.contains("已停止，正在保存本轮上下文，请稍后再操作。"),
        )
        assertFalse(StopSealNotices.PENDING.contains("保存"))
        assertFalse(StopSealNotices.TIMED_OUT.contains("保存"))
    }

    @Test
    fun `the event gate keeps streaming increments blocked`() {
        assertTrue(RunStopEventGate.isRunTerminal(AgentEvent.RunFailed(reason = "stopped")))
        assertTrue(RunStopEventGate.isRunTerminal(AgentEvent.RunFinished(round = 1, contentChars = 0)))
        assertFalse(
            RunStopEventGate.isRunTerminal(
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.TEXT,
                    index = 0,
                    deltaChars = 1,
                    delta = "x",
                )
            )
        )
        assertTrue(
            RunStopEventGate.isStreamingIncrement(
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.TEXT,
                    index = 0,
                    deltaChars = 1,
                    delta = "x",
                )
            )
        )
    }
}
