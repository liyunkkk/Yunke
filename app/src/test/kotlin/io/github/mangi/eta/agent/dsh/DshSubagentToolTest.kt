package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshSubagentToolTest {

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private fun createTool(): DshSubagentTool {
        val context = RuntimeEnvironment.getApplication()
        val terminalController = RootShellTerminalController(
            logger = NoopLogger,
        )
        return DshSubagentTool(
            context = context,
            terminalController = terminalController,
            bindingStore = InMemoryDshSessionBindingStore(),
            bridge = DshBridge(
                script = "print('{}')",
                executor = { _, _, _ -> "{}" },
            ),
            conversationId = "test-conv",
        )
    }

    @Test
    fun rejectsBlankTask() {
        val tool = createTool()
        val result = JSONObject(tool.delegate(JSONObject().put("task", "  ")))
        assertFalse(result.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENTS", result.getString("code"))
        assertTrue(result.getString("message").contains("必须提供 task"))
    }

    @Test
    fun rejectsPathTraversalInProjectPath() {
        val tool = createTool()
        val args = JSONObject()
            .put("task", "clean logs")
            .put("project_path", "/workspace/../etc")
        val result = JSONObject(tool.delegate(args))
        assertFalse(result.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENTS", result.getString("code"))
        assertTrue(result.getString("message").contains("路径穿越"))
    }

    @Test
    fun rejectsRelativeProjectPath() {
        val tool = createTool()
        val args = JSONObject()
            .put("task", "clean logs")
            .put("project_path", "workspace/app")
        val result = JSONObject(tool.delegate(args))
        assertFalse(result.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENTS", result.getString("code"))
        assertTrue(result.getString("message").contains("绝对路径"))
    }

    @Test
    fun resolveTimeoutSecondsHandlesMissingOrInvalidValues() {
        assertEquals(180, DshSubagentTool.resolveTimeoutSeconds(null))
        assertEquals(180, DshSubagentTool.resolveTimeoutSeconds(0))
        assertEquals(180, DshSubagentTool.resolveTimeoutSeconds(-10))
        assertEquals(10, DshSubagentTool.resolveTimeoutSeconds(5))
        assertEquals(300, DshSubagentTool.resolveTimeoutSeconds(300))
        assertEquals(600, DshSubagentTool.resolveTimeoutSeconds(1000))
    }
}
