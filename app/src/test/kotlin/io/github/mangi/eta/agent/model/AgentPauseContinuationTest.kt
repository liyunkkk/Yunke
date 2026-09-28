package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentPauseContinuationTest {
    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "",
        thinkingEnabled = true, reasoningEffort = ReasoningEffort.HIGH,
    )

    private fun project(events: List<AgentEvent>): List<AgentChatMessageUi> {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = emptyList<AgentChatMessageUi>()
        events.forEach { event ->
            messages = when (event) {
                is AgentEvent.AssistantBlockStart -> projector.startAssistantBlock("run", event, messages)
                is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT)
                    projector.appendTextDelta("run", event.round, event.index, event.delta, messages)
                    else if (event.kind == AgentEvent.AssistantBlockKind.THINKING)
                        projector.appendReasoningDelta("run", event.round, event.index, event.delta, messages) else messages
                is AgentEvent.AssistantBlockEnd -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT)
                    projector.finalizeTextBlock("run", event.round, event.index, event.replacementContent, messages)
                    else if (event.kind == AgentEvent.AssistantBlockKind.THINKING)
                        projector.finalizeThinkingBlock("run", event.round, event.index, event.replacementContent, messages) else messages
                is AgentEvent.AssistantReceived -> if (event.reasoningContent.isNotBlank())
                    projector.ensureCompletedThinking("run", event.round, event.reasoningContent, messages) else messages
                is AgentEvent.RunFinished -> projector.finalizeRun("run", messages)
                else -> messages
            }
        }
        return messages
    }

    @Test fun pausedSupplementStartsNewBubbleButKeepsTheSameRun() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val provider = object : AgentProviderClient {
            override val id = "paused-supplement"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val part = if (++calls == 1) "before " else "after"
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, part))
                onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = part, replaceContent = true))
                if (calls == 1) {
                    val binding = runController.register(interruptible = true) {}
                    runController.pause()
                    runController.steer("new instruction")
                    binding.close()
                } else assertTrue(request.messages.toString().contains("new instruction"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", part).put("finish_reason", "stop"))
            }
        }
        AgentLoop(config(), JSONArray().put(AgentConversationCodec.userTextMessage("task")), JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(),
            onEvent = events::add).run()
        assertEquals(2, calls)
        val projected = project(events).filterIsInstance<AgentMessageUi>()
        assertEquals(listOf("before", "after"), projected.map { it.content })
        assertNotEquals(projected[0].id, projected[1].id)
        assertEquals(projected, project(events).filterIsInstance<AgentMessageUi>())
    }

    @Test fun threePausedSegmentsStayInOneRoundAndSurviveFinalResultAndReplay() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val history = JSONArray().put(JSONObject().put("role", "user").put("content", "task"))
        val parts = listOf("first ", "second ", "third")
        var calls = 0
        val provider = object : AgentProviderClient {
            override val id = "pause-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val part = parts[calls++]
                assertEquals(ReasoningEffort.HIGH, request.config.reasoningEffort)
                onEvent(ProviderEvent.RequestStarted)
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "draft"))
                onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = part, replaceContent = true))
                if (calls < parts.size) {
                    val binding = runController.register(interruptible = true) {}
                    runController.pause()
                    runController.resume()
                    binding.close()
                }
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", part).put("finish_reason", "stop"))
            }
        }
        val result = AgentLoop(config(), history, JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(),
            onEvent = events::add, turnId = "original-turn").run()
        assertEquals("first second third", result.content)
        assertEquals(listOf(1, 1, 1), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        val projected = project(events).filterIsInstance<AgentMessageUi>()
        assertEquals(1, projected.size)
        assertEquals(result.content, projected.single().content)
        assertEquals(projected, project(events).filterIsInstance<AgentMessageUi>())
        val transcript = (0 until history.length()).map { AgentConversationCodec.fromJsonObject(history.getJSONObject(it)) }
        assertTrue(transcript.all { it.turnId == "original-turn" })
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(transcript, 1))
    }

    @Test fun pauseBeforeAnyTextKeepsModelReasoningConfiguration() {
        var calls = 0
        val controller = AgentRunController()
        val provider = object : AgentProviderClient {
            override val id = "empty-pause"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++calls == 1) {
                    val binding = runController.register(interruptible = true) {}
                    runController.pause()
                    runController.resume()
                    binding.close()
                } else assertEquals(ReasoningEffort.HIGH, request.config.reasoningEffort)
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", if (calls == 1) "" else "done").put("finish_reason", "stop"))
            }
        }
        AgentLoop(config(), JSONArray().put(AgentConversationCodec.userTextMessage("task")), JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(), onEvent = {}).run()
        assertEquals(2, calls)
    }

    @Test fun pauseDuringThinkingResumesTheThoughtInsteadOfRereadingTheConversation() {
        var calls = 0
        val requests = mutableListOf<JSONArray>()
        val controller = AgentRunController()
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("task"))
        val provider = object : AgentProviderClient {
            override val id = "thinking-pause"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests.add(JSONArray(request.messages.toString()))
                calls++
                onEvent(ProviderEvent.RequestStarted)
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, 0))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "half a thought"))
                if (calls == 1) {
                    // Paused while thinking: no text was produced yet.
                    val binding = runController.register(interruptible = true) {}
                    runController.pause()
                    runController.resume()
                    binding.close()
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("reasoning_content", "half a thought").put("finish_reason", "stop"))
                }
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "answer"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "answer")
                    .put("reasoning_content", "rest of it").put("finish_reason", "stop"))
            }
        }
        val result = AgentLoop(config(), history, JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(),
            onEvent = {}, turnId = "same-turn").run()
        assertEquals(2, calls)
        assertEquals("answer", result.content)
        // The resumed request must ask the model to continue its thought, not to re-read the turn.
        val resumed = requests[1]
        val texts = (0 until resumed.length()).map { resumed.getJSONObject(it).optString("content") }
        assertTrue(texts.contains(AgentContextCompactor.SEAMLESS_CONTINUE_THINKING_PROMPT))
        assertFalse(texts.contains(AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT))
        // It stays an internal continuation marker, so it cannot open a new compaction turn.
        val marker = AgentModelClient.ConversationMessage("user", AgentContextCompactor.SEAMLESS_CONTINUE_THINKING_PROMPT)
        assertTrue(AgentContextCompactor.isSteeringUserMessage(marker))
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(AgentTurnIdentity.migrate(listOf(
            AgentModelClient.ConversationMessage("user", "task", turnId = "original"),
            AgentModelClient.ConversationMessage("assistant", ""),
            marker,
            AgentModelClient.ConversationMessage("assistant", "answer"),
        )), 1))
        assertTrue((0 until history.length()).all {
            history.getJSONObject(it).optString(AgentTurnIdentity.JSON_KEY) == "same-turn"
        })
    }

    @Test fun resumedThinkingIsHiddenFromEventsResultAndReplayButKeptInModelHistory() {
        for (streaming in listOf(true, false)) {
            var calls = 0
            val controller = AgentRunController()
            val events = mutableListOf<AgentEvent>()
            val history = JSONArray().put(AgentConversationCodec.userTextMessage("task"))
            val provider = object : AgentProviderClient {
                override val id = "resume-hidden"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    calls++
                    onEvent(ProviderEvent.RequestStarted)
                    if (calls > 1 && streaming) {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, 7))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 7, "hidden-resume"))
                        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.THINKING, 7, content = "hidden-resume", replaceContent = true))
                        // The second reasoning block of the same resumed request stays visible.
                        if (calls == 3) {
                            onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, 9))
                            onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 9, "visible-later"))
                            onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.THINKING, 9))
                        }
                    }
                    val text = "part$calls "
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 10, text))
                    if (calls < 3) {
                        val binding = runController.register(interruptible = true) {}
                        runController.pause()
                        runController.resume()
                        binding.close()
                    }
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", text)
                        .put("reasoning_content", if (calls > 1) "hidden-resume" + if (streaming && calls == 3) "visible-later" else "" else "")
                        .put("finish_reason", "stop"))
                }
            }
            val result = AgentLoop(config(), history, JSONArray(), provider,
                AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(),
                onEvent = events::add, turnId = "same-turn").run()
            assertEquals(3, calls)
            assertEquals("part1 part2 part3", result.content)
            assertFalse(result.reasoningContent.contains("hidden-resume"))
            assertEquals(if (streaming) "visible-later" else "", result.reasoningContent)
            assertTrue(events.filterIsInstance<AgentEvent.AssistantBlockDelta>().none { it.delta.contains("hidden-resume") })
            assertTrue(events.filterIsInstance<AgentEvent.AssistantBlockEnd>().none { it.replacementContent.orEmpty().contains("hidden-resume") })
            assertTrue(events.filterIsInstance<AgentEvent.AssistantReceived>().none { it.reasoningContent.contains("hidden-resume") })
            assertEquals(listOf(1, 1, 1), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
            assertTrue(history.toString().contains("hidden-resume"))
            assertTrue((0 until history.length()).all { history.getJSONObject(it).optString(AgentTurnIdentity.JSON_KEY) == "same-turn" })
            val replayed = project(events)
            assertEquals(result.content, replayed.filterIsInstance<AgentMessageUi>().joinToString(" ") { it.content })
            val thoughts = replayed.filterIsInstance<ThinkingMessageUi>()
            assertEquals(if (streaming) listOf("visible-later") else emptyList<String>(), thoughts.map { it.content })
        }
    }

    @Test fun repeatedResumeOpeningIsRemovedFromStreamFinalHistoryAndReplayWithoutNewTurn() {
        for (streaming in listOf(true, false)) {
            var calls = 0
            val controller = AgentRunController()
            val events = mutableListOf<AgentEvent>()
            val history = JSONArray().put(AgentConversationCodec.userTextMessage("task"))
            val parts = listOf("他打开邮箱。", "他打开邮箱。点开附件。", "点开附件。内容出现了。")
            val provider = object : AgentProviderClient {
                override val id = "resume-overlap"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    val text = parts[calls++]
                    onEvent(ProviderEvent.RequestStarted)
                    if (streaming) {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
                        text.forEach { onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, it.toString())) }
                        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = text, replaceContent = true))
                    }
                    if (calls < parts.size) {
                        val binding = runController.register(interruptible = true) {}
                        runController.pause()
                        runController.resume()
                        binding.close()
                    }
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"))
                }
            }
            val result = AgentLoop(config(), history, JSONArray(), provider,
                AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(),
                onEvent = events::add, turnId = "same-turn").run()
            val expected = "他打开邮箱。点开附件。内容出现了。"
            assertEquals(expected, result.content)
            val assistantHistory = (0 until history.length()).map { history.getJSONObject(it) }.filter { it.optString("role") == "assistant" }
            assertEquals(expected, assistantHistory.joinToString("") { it.getString("content") })
            assertTrue(assistantHistory.all { it.getString(AgentTurnIdentity.JSON_KEY) == "same-turn" })
            assertEquals(listOf(1, 1, 1), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
            if (streaming) {
                assertEquals(expected, project(events).filterIsInstance<AgentMessageUi>().joinToString("") { it.content })
                assertEquals(expected, events.filterIsInstance<AgentEvent.AssistantBlockDelta>()
                    .filter { it.kind == AgentEvent.AssistantBlockKind.TEXT }.joinToString("") { it.delta })
                val replay = project(events)
                assertEquals(project(events), replay)
            }
        }
    }

    @Test fun legacyResumeAndSteeringRecordsDoNotCreateNewCompressionTurns() {
        val history = AgentTurnIdentity.migrate(listOf(
            AgentModelClient.ConversationMessage("user", "task", turnId = "original"),
            AgentModelClient.ConversationMessage("assistant", "partial"),
            AgentModelClient.ConversationMessage("user", AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT),
            AgentModelClient.ConversationMessage("user", AgentContextCompactor.steeringUserContent("more")),
            AgentModelClient.ConversationMessage("assistant", "stopped text"),
        ))
        assertTrue(history.all { it.turnId == "original" })
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 1))
    }
}
