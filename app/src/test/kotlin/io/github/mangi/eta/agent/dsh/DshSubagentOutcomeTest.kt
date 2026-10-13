package io.github.mangi.eta.agent.dsh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSubagentOutcomeTest {

    @Test
    fun successfulOutcomeSerializesAllRequiredFields() {
        val outcome = DshSubagentOutcome(
            ok = true,
            task = "重构通信模块",
            projectPath = "/workspace/my-project",
            sessionId = "dsh-sess-42",
            content = "已将原有模块重构为协程接口，测试全部通过。",
            gitModifiedFiles = "M app/src/main/Bridge.kt\nM app/build.gradle.kts",
            gitDiffStat = "2 files changed, 25 insertions(+), 10 deletions(-)",
        )

        val json = JSONObject(outcome.toJson())

        assertTrue(json.getBoolean("ok"))
        assertEquals("delegate_to_dsh", json.getString("tool"))
        assertEquals("重构通信模块", json.getString("task"))
        assertEquals("/workspace/my-project", json.getString("project_path"))
        assertEquals("dsh-sess-42", json.getString("session_id"))
        assertEquals("已将原有模块重构为协程接口，测试全部通过。", json.getString("content"))
        assertEquals("已将原有模块重构为协程接口，测试全部通过。", json.getString("output"))
        assertEquals("M app/src/main/Bridge.kt\nM app/build.gradle.kts", json.getString("git_modified_files"))
        assertEquals("2 files changed, 25 insertions(+), 10 deletions(-)", json.getString("git_diff_stat"))
        assertTrue(json.getString("message").contains("执行完成并已交付变更"))
        assertFalse("成功时不应带错误码", json.has("code"))
        assertFalse("成功时不应带 error_code", json.has("error_code"))
    }

    @Test
    fun failedOutcomeIncludesErrorDetails() {
        val outcome = DshSubagentOutcome(
            ok = false,
            task = "运行构建",
            projectPath = "/workspace/app",
            sessionId = "dsh-sess-fail",
            content = "构建失败输出",
            gitModifiedFiles = "",
            gitDiffStat = "",
            errorCode = "PROMPT_TIMEOUT",
            errorMessage = "执行超时 (180s)",
        )

        val json = JSONObject(outcome.toJson())

        assertFalse(json.getBoolean("ok"))
        assertEquals("delegate_to_dsh", json.getString("tool"))
        assertEquals("PROMPT_TIMEOUT", json.getString("code"))
        assertEquals("PROMPT_TIMEOUT", json.getString("error_code"))
        assertEquals("执行超时 (180s)", json.getString("error_message"))
        assertTrue(json.getString("message").contains("PROMPT_TIMEOUT") || json.getString("message").contains("执行超时"))
        assertEquals("无 git 变更或非 git 仓库", json.getString("git_modified_files"))
        assertEquals("无代码增删差异", json.getString("git_diff_stat"))
    }

    @Test
    fun longContentIsTruncatedToMaxChars() {
        val longText = "a".repeat(5000) + "END"
        val outcome = DshSubagentOutcome(
            ok = true,
            task = "t",
            projectPath = "/workspace",
            sessionId = null,
            content = longText,
            gitModifiedFiles = "",
            gitDiffStat = "",
        )

        val json = JSONObject(outcome.toJson())
        val output = json.getString("output")
        assertEquals(4000, output.length)
        assertTrue(output.endsWith("END"))
    }
}
