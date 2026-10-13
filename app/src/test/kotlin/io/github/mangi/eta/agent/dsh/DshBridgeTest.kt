package io.github.mangi.eta.agent.dsh

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshBridgeTest {

    @Test
    fun quoteEscapesSingleQuotesForPosixSh() {
        assertEquals("''", DshBridge.quote(""))
        assertEquals("'hello'", DshBridge.quote("hello"))
        assertEquals("'don'\"'\"'t'", DshBridge.quote("don't"))
        assertEquals("''\"'\"'a'\"'\"'b'\"'\"''", DshBridge.quote("'a'b'"))
        assertEquals("'foo \"bar\" baz'", DshBridge.quote("foo \"bar\" baz"))
        assertEquals("'line1\nline2'", DshBridge.quote("line1\nline2"))
    }

    @Test
    fun buildShellCommandInjectsEnvAndPythonFallback() {
        val script = "print('hello')"
        val argsJson = "{\"action\":\"run\",\"task\":\"fix code\"}"
        val command = DshBridge.buildShellCommand(
            script = script,
            argsJson = argsJson,
            runtimeBin = "/usr/local/bin/dsh",
            dshHome = "/root/.dsh",
        )

        // 验证 python3 缺失兜底探测
        assertTrue("必须包含 python3 探测兜底", command.contains("command -v python3 >/dev/null 2>&1 ||"))
        assertTrue("兜底必须以 JSON 输出 RUNTIME_MISSING 并退出", command.contains("RUNTIME_MISSING"))

        // 验证环境变量注入
        assertTrue("必须注入 DSH_RUNTIME_BIN", command.contains("DSH_RUNTIME_BIN='/usr/local/bin/dsh'"))
        assertTrue("必须注入 DSH_HOME", command.contains("DSH_HOME='/root/.dsh'"))

        // 验证 python3 -I -c 命令与参数引用
        assertTrue("必须包含 python3 -I -c", command.contains("python3 -I -c"))
        assertTrue("必须包含转义后的脚本", command.contains(DshBridge.quote(script)))
        assertTrue("必须包含转义后的 JSON 参数", command.contains(DshBridge.quote(argsJson)))
    }

    @Test
    fun parseBridgeOutputHandlesSuccessfulResponse() {
        val stdout = """
            {"ok":true,"session_id":"sess-123","resumed":false,"response":"完成修改并测试通过","response_truncated":false,"stop_reason":"end_turn","tool_calls":[{"title":"write","status":"completed"}],"usage":{"used":1000,"size":50000},"elapsed_seconds":5.2}
        """.trimIndent()

        val result = DshBridge.parseBridgeOutput(stdout)

        assertTrue(result.ok)
        assertEquals("sess-123", result.sessionId)
        assertFalse(result.resumed)
        assertEquals("完成修改并测试通过", result.response)
        assertFalse(result.responseTruncated)
        assertEquals("end_turn", result.stopReason)
    }

    @Test
    fun parseBridgeOutputExtractsJsonFromNoisyStdout() {
        val stdout = """
            [dsh-bridge] initializing ACP...
            {"ok":true,"session_id":"sess-noise","resumed":true,"response":"ok","response_truncated":false,"stop_reason":"end_turn"}
            
        """.trimIndent()

        val result = DshBridge.parseBridgeOutput(stdout)

        assertTrue(result.ok)
        assertEquals("sess-noise", result.sessionId)
        assertTrue(result.resumed)
        assertEquals("ok", result.response)
    }

    @Test
    fun parseBridgeOutputMapsStandardRefusedCodes() {
        val runtimeMissing = """{"ok":false,"code":"RUNTIME_MISSING","hint":"install deepseek-harness-runtime-bin"}"""
        val parsedRuntime = DshBridge.parseBridgeOutput(runtimeMissing)
        assertFalse(parsedRuntime.ok)
        assertEquals("RUNTIME_MISSING", parsedRuntime.errorCode)
        assertTrue(parsedRuntime.errorMessage?.contains("install deepseek-harness-runtime-bin") == true)

        val promptTimeout = """{"ok":false,"code":"PROMPT_TIMEOUT","session_id":"s-time","partial_response":"thinking done","elapsed_seconds":180.0}"""
        val parsedTimeout = DshBridge.parseBridgeOutput(promptTimeout)
        assertFalse(parsedTimeout.ok)
        assertEquals("PROMPT_TIMEOUT", parsedTimeout.errorCode)
        assertEquals("s-time", parsedTimeout.sessionId)
        assertTrue(parsedTimeout.errorMessage?.contains("thinking done") == true)

        val endpointMissing = """{"ok":false,"code":"ENDPOINT_MISSING","hint":"provide key_file or DEEPSEEK_API_KEY"}"""
        val parsedEndpoint = DshBridge.parseBridgeOutput(endpointMissing)
        assertFalse(parsedEndpoint.ok)
        assertEquals("ENDPOINT_MISSING", parsedEndpoint.errorCode)
    }

    @Test
    fun parseBridgeOutputHandlesCorruptedOrEmptyOutput() {
        val emptyResult = DshBridge.parseBridgeOutput("")
        assertFalse(emptyResult.ok)
        assertEquals("DSH_BRIDGE_FAILED", emptyResult.errorCode)

        val nonJsonResult = DshBridge.parseBridgeOutput("fatal: python crashed")
        assertFalse(nonJsonResult.ok)
        assertEquals("DSH_BRIDGE_FAILED", nonJsonResult.errorCode)
    }

    @Test
    fun parseTerminalOutputHandlesTimeoutEnvelope() {
        val timeoutEnvelope = JSONObject()
            .put("ok", false)
            .put("timed_out", true)
            .put("exit_code", -2)
            .put("stdout", "")
            .put("stderr", "")
            .toString()

        val result = DshBridge.parseTerminalOutput(timeoutEnvelope)
        assertFalse(result.ok)
        assertEquals("PROMPT_TIMEOUT", result.errorCode)
    }

    @Test
    fun parseTerminalOutputHandlesProcessCrashEnvelope() {
        val crashEnvelope = JSONObject()
            .put("ok", false)
            .put("timed_out", false)
            .put("exit_code", 137)
            .put("stdout", "")
            .put("stderr", "Killed by OOM")
            .toString()

        val result = DshBridge.parseTerminalOutput(crashEnvelope)
        assertFalse(result.ok)
        assertEquals("RUNTIME_EXITED", result.errorCode)
        assertTrue(result.errorMessage?.contains("Killed by OOM") == true)
    }

    @Test
    fun dshSettingsLoadFallbacksToDefaults() {
        val settings = DshSettings.load(null)
        assertEquals("https://wb2.c44lyk.dpdns.org/v1", settings.endpoint)
        assertEquals("/root/.dsh/key", settings.keyFilePath)
        assertEquals("deepseek-v4-flash", settings.model)
        assertEquals("high", settings.effort)
    }

    @Test
    fun dshSettingsReadsFromPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(DshSettings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(DshSettings.PREF_ENDPOINT, "https://api.deepseek.com/v1")
            .putString(DshSettings.PREF_KEY_FILE_PATH, "/root/.keys/custom_key")
            .putString(DshSettings.PREF_MODEL, "deepseek-coder")
            .putString(DshSettings.PREF_EFFORT, "medium")
            .commit()

        val settings = DshSettings.load(context)
        assertEquals("https://api.deepseek.com/v1", settings.endpoint)
        assertEquals("/root/.keys/custom_key", settings.keyFilePath)
        assertEquals("deepseek-coder", settings.model)
        assertEquals("medium", settings.effort)
    }
}
