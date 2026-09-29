package io.github.mangi.eta.ui.model

import android.app.Application
import io.github.mangi.eta.agent.model.AgentCompactionArchive
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConversationToolEvidenceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun tool(id: String = "run-a-tool-1-call-a", name: String = "terminal") = ToolActivityMessageUi(
        id = id, toolName = name, status = ToolActivityStatusUi.Success,
        argumentsSummary = "command preview", resultSummary = "short summary…",
    )
    private fun history(result: String, turn: String = "run-a", name: String = "terminal") = listOf(
        ConversationMessage("assistant", turnId = turn, toolCallsJson = JSONArray().put(JSONObject()
            .put("id", "call-a").put("function", JSONObject().put("name", name)
                .put("arguments", "{\"command\":\"ls\"}"))).toString()),
        ConversationMessage("tool", result, toolCallId = "call-a", turnId = turn),
    )
    @Test fun exportsFullStoredResultAndDistinctImmutableSnapshots() {
        val tool = tool()
        val raw = "正文🙂".repeat(3000) + "END"
        val evidence = ConversationToolEvidence(listOf(tool)).apply { add(history(raw), "history") }
        fun snapshot() = ConversationMention.transcript(listOf(tool), filesDir = temp.root,
            conversationId = "conv-a", toolEvidence = evidence)
        val first = snapshot()
        val firstFile = File(first.substringAfter("Details file: ").substringBefore('\n'))
        assertTrue(firstFile.readText().endsWith(raw))
        assertTrue(first.contains("Complete stored tool record"))
        assertFalse(first.contains(raw))
        val secondFile = File(snapshot().substringAfter("Details file: ").substringBefore('\n'))
        assertNotEquals(firstFile, secondFile)
        assertTrue(firstFile.readText().endsWith(raw))
    }
    @Test fun missingOriginalIsExplicitlySummaryOnly() {
        val text = ConversationMention.transcript(listOf(tool()), filesDir = temp.root, conversationId = "a")
        assertTrue(text.contains("only the UI summary"))
        val file = File(text.substringAfter("Details file: ").substringBefore('\n'))
        val body = file.readText()
        assertTrue(body.contains("仅有摘要"))
        assertTrue(body.contains("Result summary:"))
    }
    @Test fun redactedAndSensitiveResultsAreNeverExported() {
        val message = tool(name = "wifi_credentials")
        val evidence = ConversationToolEvidence(listOf(message)).apply {
            add(history("secret", name = "wifi_credentials"), "history")
        }
        assertNull(evidence.original(message.id))
        val normal = tool()
        val redacted = ConversationToolEvidence(listOf(normal)).apply {
            add(history("[敏感工具参数与原始结果仅供当前回合使用，未写入持久会话]"), "history")
            add(history("earlier unredacted"), "archive")
        }
        assertNull(redacted.original(normal.id))
    }
    @Test fun identicalCallIdsInOtherTurnsDoNotOverrideExactTurn() {
        val message = tool()
        val evidence = ConversationToolEvidence(listOf(message)).apply {
            add(history("correct"), "history")
            add(history("other", turn = "run-b"), "archive")
        }
        assertEquals("correct", evidence.original(message.id)?.result)
        val legacy = tool(id = "legacy-tool-1-call-a")
        val ambiguous = ConversationToolEvidence(listOf(legacy)).apply {
            add(history("first"), "history")
            add(history("second", turn = "run-b"), "archive")
        }
        assertNull(ambiguous.original(legacy.id))
    }
    @Test fun singleDifferentTurnIsNotTreatedAsLegacy() {
        val message = tool()
        val evidence = ConversationToolEvidence(listOf(message)).apply {
            add(history("wrong", turn = "run-b"), "history")
        }
        assertNull(evidence.original(message.id))
        val legacy = ConversationToolEvidence(listOf(message)).apply {
            add(history("legacy", turn = ""), "history")
        }
        assertEquals("legacy", legacy.original(message.id)?.result)
    }

    @Test fun prunedHistoryFallsBackToVerifiedSourceArchiveOnly() {
        val message = tool()
        val evidence = ConversationToolEvidence(listOf(message)).apply {
            add(history("head [Eta tool output pruned; checkpoint] tail"), "history")
        }
        val archive = AgentCompactionArchive(temp.root, "a")
        archive.save(history("original"))
        AgentCompactionArchive(temp.root, "b").visitForConversationMention(evidence::add)
        assertNull(evidence.original(message.id))
        archive.visitForConversationMention(evidence::add)
        assertEquals("original", evidence.original(message.id)?.result)
    }
    @Test fun corruptedArchiveIsNotAccepted() {
        val archive = AgentCompactionArchive(temp.root, "a")
        val checkpoint = archive.save(history("original"))
        temp.root.walkTopDown().first { it.name == "$checkpoint.json" }.appendText("corrupt")
        var visited = false
        archive.visitForConversationMention { _, _ -> visited = true }
        assertFalse(visited)
    }
}
