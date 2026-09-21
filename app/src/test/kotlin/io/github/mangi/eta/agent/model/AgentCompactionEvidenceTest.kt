package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionEvidenceTest {
    private fun pair(id: String, state: String, order: Int, command: String = "gh run view $id -R owner/repo --json status,conclusion,headSha") = listOf(
        AgentModelClient.ConversationMessage("assistant", toolCallsJson = JSONArray().put(JSONObject().put("id", "call$order")
            .put("function", JSONObject().put("name", "terminal").put("arguments", JSONObject().put("command", command).toString()))).toString()),
        AgentModelClient.ConversationMessage("tool", toolCallId = "call$order", content = JSONObject().put("ok", true).put("exit_code", 0)
            .put("stdout", state).toString()),
    )
    private fun summary(pending: String = "(none)", next: String = "(none)") = "[对话摘要]\n" +
        AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- " + when (it) {
            "Pending Jobs" -> pending; "Next Step" -> next; else -> "(none)"
        } }

    @Test fun runningThenSuccessRemainOrderedIndependentOfIntermediateSummary() {
        val source = pair("35557982887", """{"status":"in_progress"}""", 0) +
            pair("35557982887", """{"conclusion":"success","headSha":"6ba5280132b6a62b73b31a9ecde31037c7f62691"}""", 1)
        val evidence = AgentCompactionEvidence.collect(source)
        assertEquals(listOf(1, 3), evidence.runs.map { it.order })
        assertTrue(evidence.runs.first().status == "in_progress" && evidence.runs.last().conclusion == "success")
        val output = evidence.attachAndValidate(summary())
        assertTrue(output.contains("35557982887")); assertTrue(output.contains("success"))
        assertFalse(output.contains("in_progress"))
        assertThrows(IllegalArgumentException::class.java) {
            evidence.attachAndValidate(summary("等待工作流 35557982887 完成"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence.attachAndValidate(summary(next = "继续轮询 35557982887"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence.attachAndValidate(summary("工作流 35557982887 已经启动，仍在运行，继续轮询"))
        }
        evidence.attachAndValidate(summary("工作流 35557982887 无需继续轮询"))
        evidence.attachAndValidate(summary("下载工作流 35557982887 的 APK")) // build != download
        evidence.attachAndValidate(summary("等待工作流 99999999999 完成")) // identity matters
    }

    @Test fun laterRerunOverridesEarlierSuccessAndSecretsAreNotCopied() {
        val source = pair("35557982887", """{"conclusion":"success","apiKey":"SECRET"}""", 0) +
            pair("35557982887", """{"status":"in_progress"}""", 1) +
            AgentModelClient.ConversationMessage("user", "修复生图参数") +
            AgentModelClient.ConversationMessage("user", "password=PRIVATE")
        val evidence = AgentCompactionEvidence.collect(source)
        evidence.attachAndValidate(summary("等待工作流 35557982887 完成"))
        assertFalse(evidence.prompt().contains("SECRET")); assertFalse(evidence.prompt().contains("PRIVATE"))
        assertTrue(evidence.prompt().contains("修复生图参数"))
    }

    @Test fun genericSuccessWrongRunFailedShellAndQuotedCommandAreNotPromoted() {
        val source = pair("35557982887", """{"ok":true}""", 0) +
            pair("35557982887", """{"databaseId":999999,"conclusion":"success"}""", 1) +
            pair("35557982887", """{"conclusion":"success"}""", 2, "echo 'gh run view 35557982887'") +
            pair("35557982887", """{"conclusion":"success"}""", 3).map {
                if (it.role == "tool") it.copy(content = JSONObject(it.content).put("exit_code", 1).toString()) else it
            }
        assertTrue(AgentCompactionEvidence.collect(source).runs.isEmpty())
    }

    @Test fun excessiveRecognisedEvidenceFailsClosedAndSourceIsUnchanged() {
        val source = (0..64).flatMap { pair("35557982887", """{"status":"in_progress"}""", it) }
        val before = source.toList()
        assertThrows(IllegalArgumentException::class.java) { AgentCompactionEvidence.collect(source) }
        assertEquals(before, source)
    }
    @Test fun sameNumericIdInDifferentRepositoriesIsNotUsedForAmbiguousPendingGuard() {
        val source = pair("35557982887", "{\"conclusion\":\"success\"}", 0) +
            pair("35557982887", "{\"status\":\"in_progress\"}", 1,
                "gh run view 35557982887 -R other/repo --json status,conclusion")
        val evidence = AgentCompactionEvidence.collect(source)
        assertEquals(2, evidence.runs.size)
        evidence.attachAndValidate(summary("等待工作流 35557982887 完成"))
    }

}
