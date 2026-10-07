package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual event entry -> messages -> visible -> timeline -> rows, with no live runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AgentRunFinishedProductionRegressionTest {
    @Test fun usageThenTerminalAndReplayKeepAllPayloadsAndOriginalSnapshots() {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val app = AgentAppState(context, scope)
            val originalMessages = (List<AgentChatMessageUi>(4096) {
                UserMessageUi("old-$it", "old-$it")
            } + AgentMessageUi("assistant-live-1-0", "hello🙂\n", true, renderMarkdown = false))
                .incrementalSnapshot()
            call(app, "updateConversation", "c", AgentChatHomeUiState(
                messages = originalMessages, input = "", isStreaming = true, thinkingEnabled = false,
            ), false)
            call(app, "bindUsageRun", "live", "c")
            fun send(event: AgentEvent) { call(app, "applyRunEvent", "live", event, false, true) }
            fun current() = call(app, "conversationStateForRun", "live") as AgentChatHomeUiState
            send(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 5000, outputTokens = 22)))
            val before = current().messages
            val hash = before.hashCode()
            val originalEntries = before.toTimelineEntries()
            val originalRows = originalEntries.toLazyTimelineRows(emptyMap(), true)
            val usage = (before.last() as AgentMessageUi).usage
            send(AgentEvent.RunFinished(1, 8, generatedAtMillis = 12345L))
            val after = current().messages
            val oracle = before.map { if (it is AgentMessageUi && it.id.startsWith("assistant-live-"))
                it.copy(content = it.content.trimEnd(), isStreaming = false, renderMarkdown = true,
                    generatedAtMillis = it.generatedAtMillis ?: 12345L) else it }
            assertEquals(oracle, after)
            assertEquals(usage, (after.last() as AgentMessageUi).usage)
            assertEquals(5000, current().livePromptTokens)
            assertEquals(12345L, (after.last() as AgentMessageUi).generatedAtMillis)
            assertEquals("hello🙂\n", (before.last() as AgentMessageUi).content)
            assertEquals(hash, before.hashCode())
            assertEquals(before.toTimelineEntries(), originalEntries)
            assertEquals(originalEntries.toLazyTimelineRows(emptyMap(), true), originalRows)
            val filter = AgentVisibleMessagesCache()
            var fullBuilds = 0
            val timeline = AgentTimelineProjectionCache { fullBuilds++; it.toTimelineEntries() }
            val rows = AgentTimelineRowsCache()
            timeline.project(filter.project(before, null))
            val visible = filter.project(after, null)
            val entries = timeline.project(visible)
            assertEquals(1, fullBuilds) // pure terminal payload keeps the original scan-compatible route
            assertEquals(visible.toTimelineEntries(), entries)
            assertEquals(entries.toLazyTimelineRows(emptyMap(), false), rows.project(entries, emptyMap(), false))
            send(AgentEvent.RunFinished(1, 8, generatedAtMillis = 99999L))
            assertEquals(after, current().messages)
            assertEquals(5000, current().livePromptTokens)
        } finally { scope.cancel(); EtaDatabase.closeForTests() }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
