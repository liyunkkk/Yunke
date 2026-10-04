package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInvalidToolArgumentsGuardTest {
    @Test fun thirdRejectedCallExhaustsBudgetWithExplicitChineseStopMessage() {
        val guard = AgentInvalidToolArgumentsGuard()
        repeat(2) {
            assertEquals(AgentInvalidToolArgumentsGuard.FAILURE_CODE, reject(guard).code)
            assertNull(guard.stopMessage)
        }
        val rejection = reject(guard)
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, rejection.code)
        assertEquals(rejection.message, guard.stopMessage)
        assertTrue(rejection.message.contains(AgentInvalidToolArgumentsGuard.STOP_CODE))
        assertTrue(rejection.message.contains("纠错预算耗尽"))
        assertTrue(rejection.message.contains("本次调用未执行"))
        assertTrue(rejection.message.contains("本批工具结果完整配对后按错误重连策略处理"))
    }

    @Test fun validArgumentsResetOnlyTheirOwnUnexhaustedBudget() {
        val guard = AgentInvalidToolArgumentsGuard()
        repeat(2) { reject(guard) }
        repeat(2) { reject(guard, "read_file") }
        guard.validated("run_command")
        repeat(2) { assertEquals(AgentInvalidToolArgumentsGuard.FAILURE_CODE, reject(guard).code) }
        assertNull(guard.stopMessage)
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, reject(guard, "read_file").code)
    }

    @Test fun unrelatedValidToolsCannotResetTheFailingTool() {
        val guard = AgentInvalidToolArgumentsGuard()
        repeat(2) {
            reject(guard)
            guard.validated("read_file")
        }
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, reject(guard).code)
    }

    @Test fun allUnknownNamesShareOneBucketSeparateFromDeclaredNames() {
        val guard = AgentInvalidToolArgumentsGuard()
        reject(guard, "unknown_tool", declared = false)
        // Even a declared tool with this literal name must not reset the unknown bucket.
        guard.validated("unknown_tool")
        reject(guard, "arbitrary_new_name", declared = false)
        reject(guard, "unknown_tool", declared = true)
        val rejection = reject(guard, "   ", declared = false)
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, rejection.code)
        assertTrue(rejection.message.contains("所有未知名称共用预算"))
    }

    @Test fun whitespaceInDeclaredNameDoesNotCreateNewBudgetKeys() {
        val guard = AgentInvalidToolArgumentsGuard()
        reject(guard, "run_command")
        reject(guard, " run_command ")
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, reject(guard, "\trun_command\n").code)
    }

    @Test fun exhaustionCannotBeUndoneByLaterValidArgumentsInTheSameBatch() {
        val guard = AgentInvalidToolArgumentsGuard()
        repeat(3) { reject(guard) }
        val firstStop = guard.stopMessage
        guard.validated("run_command")
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, reject(guard).code)
        repeat(3) { reject(guard, "read_file") }
        assertEquals(firstStop, guard.stopMessage)
    }

    @Test fun budgetIsLocalToEachRun() {
        val exhausted = AgentInvalidToolArgumentsGuard()
        repeat(3) { reject(exhausted) }
        assertNotNull(exhausted.stopMessage)
        val fresh = AgentInvalidToolArgumentsGuard()
        repeat(2) { assertEquals(AgentInvalidToolArgumentsGuard.FAILURE_CODE, reject(fresh).code) }
        assertNull(fresh.stopMessage)
    }

    @Test fun validCallsHaveNoLifetimeLimit() {
        val guard = AgentInvalidToolArgumentsGuard()
        repeat(1_000) {
            reject(guard)
            guard.validated("run_command")
        }
        assertNull(guard.stopMessage)
    }

    private fun reject(
        guard: AgentInvalidToolArgumentsGuard,
        name: String = "run_command",
        declared: Boolean = true,
    ) = guard.reject(name, declared, "arguments.command 是必填字段")
}
