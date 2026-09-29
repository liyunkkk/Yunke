package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentPollGuardTest {
    private fun call(taskId: String?): AgentModelClient.ToolCall {
        val args = JSONObject()
        if (taskId != null) args.put("task_id", taskId)
        return AgentModelClient.ToolCall("call", SubAgentPollGuard.TOOL, args.toString())
    }

    private fun result(status: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(JSONObject().put("status", status).toString())

    private class Clock {
        var now = 0L
        fun guard(enabled: Boolean = true) = SubAgentPollGuard(enabled = enabled, now = { now })
    }

    @Test fun rejectionClearsOnceSuggestedWaitElapsed() {
        val clock = Clock()
        val guard = clock.guard()
        val query = call("t1")
        // 首次查询没有观测记录，直接放行。
        assertNull(guard.reject(query))
        guard.observe(query, result("running"))
        // 首档 20 秒内重复查询被拒。
        assertNotNull(guard.reject(query))
        // 守规矩地等到建议时间即可通过。
        clock.now = 20_000L
        assertNull(guard.reject(query))
    }

    @Test fun terminalStateIsReleasedPermanently() {
        val clock = Clock()
        val guard = clock.guard()
        val query = call("t2")
        guard.observe(query, result("completed"))
        assertNull(guard.reject(query))
        assertNull(guard.reject(query))
        // 运行中受限，转为终态后立即永久放行。
        guard.observe(query, result("running"))
        assertNotNull(guard.reject(query))
        guard.observe(query, result("failed"))
        assertNull(guard.reject(query))
        guard.release("t2")
        assertNull(guard.reject(query))
    }

    @Test fun listQueriesAndUnknownTasksAreExempt() {
        val clock = Clock()
        val guard = clock.guard()
        guard.observe(call("t3"), result("running"))
        // 不带 task_id 的列表查询完全豁免。
        assertNull(guard.reject(call(null)))
        // 其它任务的查询不受本任务节奏影响。
        assertNull(guard.reject(call("other")))
        // 仍受本任务约束。
        assertNotNull(guard.reject(call("t3")))
    }

    @Test fun threeConsecutiveStrikesSuspendForSixtySecondsThenRecover() {
        val clock = Clock()
        val guard = clock.guard()
        val query = call("t4")
        guard.observe(query, result("running"))
        assertFalse(guard.suspended)
        assertNotNull(guard.reject(query))
        assertFalse(guard.suspended)
        assertNotNull(guard.reject(query))
        assertFalse(guard.suspended)
        // 第三次连续违规触发有时限挂起。
        assertNotNull(guard.reject(query))
        assertTrue(guard.suspended)
        clock.now = 60_000L
        assertFalse(guard.suspended)
    }

    @Test fun disabledGuardKeepsExistingBehavior() {
        val clock = Clock()
        val guard = clock.guard(enabled = false)
        val query = call("t5")
        assertNull(guard.reject(query))
        guard.observe(query, result("running"))
        assertNull(guard.reject(query))
        assertFalse(guard.suspended)
        // 开关默认关闭：未显式开启时读取为 false。
        assertFalse(SubAgentPollGuard.enabled())
    }
}
