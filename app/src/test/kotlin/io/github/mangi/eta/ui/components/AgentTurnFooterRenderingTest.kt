package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Exercises the real lazy-list call site, not a second footer projection. CI only. */
@RunWith(RobolectricTestRunner::class)
// Match the native shader runtime used by AgentWorkProcessCardDrawTest.
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentTurnFooterRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val callbacks = mutableListOf<String>()

    @Before fun initPreferences() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
    }

    @Test fun actionsFollowCollapsedExpandedAndLateWorkWithoutChangingCallbackIds() {
        val answer = AgentMessageUi("answer", "Original answer", renderMarkdown = false)
        val messages = mutableStateOf<List<AgentChatMessageUi>>(listOf(
            UserMessageUi("user", "Question"), answer,
            ThinkingMessageUi("thinking", "All reasoning", false), tool("one", "First step"),
        ))
        showConversation(messages)
        footer(answer.id).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.copy_answer)).assertCountEquals(1)
        // User actions still live on the original bubble.
        compose.onNodeWithContentDescription(text(R.string.ui_edit_a7f814)).performClick()
        assertEquals(listOf("edit:user"), callbacks)
        assertBelow(footer(answer.id), compose.onNodeWithText(answer.content))

        compose.onNodeWithContentDescription(text(R.string.work_expand)).performClick()
        compose.onNodeWithText("First step").assertIsDisplayed()
        assertBelow(footer(answer.id), compose.onNodeWithText("First step"))
        compose.runOnIdle { messages.value = messages.value + tool("two", "Late step") }
        compose.onNodeWithText("Late step").assertIsDisplayed()
        assertBelow(footer(answer.id), compose.onNodeWithText("Late step"))
        footerAction(answer.id, R.string.ui_branch_conversation).performClick()
        footerAction(answer.id, R.string.ui_regenerate_reply_84a7d9).performClick()
        footerAction(answer.id, R.string.ui_delete_this_conversation_3f351b).performClick()
        assertEquals(listOf("edit:user", "branch:answer", "regenerate:answer", "delete:answer"), callbacks)
        compose.onNodeWithContentDescription(text(R.string.work_collapse)).performClick()
        footer(answer.id).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.copy_answer)).assertCountEquals(1)
    }

    @Test fun stoppedNoticeOwnsTheOnlyFooterAfterItsTrailingSteps() {
        val notice = SystemNoticeMessageUi("stopped", SystemNoticeCode.Stopped, "Original stop detail")
        val messages = mutableStateOf<List<AgentChatMessageUi>>(listOf(
            UserMessageUi("user", "Question"),
            AgentMessageUi("partial", "Partial answer", renderMarkdown = false),
            notice, tool("late", "Step after stop"),
        ))
        showConversation(messages)
        footer("partial").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.work_expand)).performClick()
        assertBelow(footer(notice.id), compose.onNodeWithText("Step after stop"))
        compose.onAllNodesWithContentDescription(text(R.string.copy_answer)).assertCountEquals(1)
        footerAction(notice.id, R.string.ui_regenerate_reply_84a7d9).performClick()
        footerAction(notice.id, R.string.ui_delete_this_conversation_3f351b).performClick()
        assertEquals(listOf("regenerate:stopped", "delete:stopped"), callbacks)
    }

    @Test fun completedDividerAndUsagePlaceholderKeepTheRealAnswerFooter() {
        val answer = AgentMessageUi("answer", "Copy and speak the answer", renderMarkdown = false)
        val completed = SystemNoticeMessageUi("completed", SystemNoticeCode.Completed)
        val messages = mutableStateOf<List<AgentChatMessageUi>>(listOf(
            UserMessageUi("user", "Question"), answer,
            tool("step", "Final step"), completed,
            AgentMessageUi("usage", "", usage = TokenUsageUi(outputTokens = 12)),
        ))
        showConversation(messages)
        footer(answer.id).assertIsDisplayed()
        footer(completed.id).assertDoesNotExist()
        footer("usage").assertDoesNotExist()
        assertBelow(footer(answer.id), compose.onNodeWithText(text(R.string.system_notice_completed)))
        compose.onAllNodesWithContentDescription(text(R.string.copy_answer)).assertCountEquals(1)
        footerAction(answer.id, R.string.ui_delete_this_conversation_3f351b).performClick()
        assertEquals(listOf("delete:answer"), callbacks)
    }

    @Test fun completedWithoutBodyStillRendersActionsAfterTheDivider() {
        val completed = SystemNoticeMessageUi("completed", SystemNoticeCode.Completed)
        val messages = mutableStateOf<List<AgentChatMessageUi>>(listOf(
            UserMessageUi("user", "Question"), tool("step", "Final step"), completed,
        ))
        showConversation(messages)
        assertBelow(footer(completed.id), compose.onNodeWithText(text(R.string.system_notice_completed)))
        footerAction(completed.id, R.string.ui_regenerate_reply_84a7d9).performClick()
        assertEquals(listOf("regenerate:completed"), callbacks)
    }

    @Test fun terminalFooterWaitsForItsAnswerRevealButNotForAnotherTurn() {
        val oldAnswer = AgentMessageUi("old", "Previous answer", renderMarkdown = false)
        val answer = AgentMessageUi("answer", "Pending answer")
        val stopped = SystemNoticeMessageUi("stopped", SystemNoticeCode.Stopped)
        val messages = mutableStateOf<List<AgentChatMessageUi>>(listOf(
            UserMessageUi("u1", "First question"), oldAnswer,
            UserMessageUi("u2", "Second question"), answer, stopped,
        ))
        val retained = StreamingMarkdownState()
        val states = mutableStateMapOf(answer.id to retained)
        // A paused parser cannot complete this target by itself. Publish the same
        // exact-source completion signal that AgentMessageBlock normally emits.
        showConversation(messages, states = states, paused = true)
        footer(oldAnswer.id).assertIsDisplayed()
        footer(stopped.id).assertIsDisplayed()
        footerAction(stopped.id, R.string.ui_branch_conversation).assertIsEnabled()
        compose.runOnIdle { retained.revealedContent = "Older content" }
        footer(stopped.id).assertIsDisplayed()
        compose.runOnIdle { retained.revealedContent = answer.content }
        footer(stopped.id).assertIsDisplayed()
    }

    @Test fun streamingAssistantCanBranchButCannotDeleteOrRegenerate() {
        val streaming = mutableStateOf(true)
        val messages = mutableStateOf<List<AgentChatMessageUi>>(listOf(
            UserMessageUi("user", "Question"),
            AgentMessageUi("answer", "Intermediate answer", renderMarkdown = false),
            tool("step", "Work after the answer"),
        ))
        showConversation(messages, streaming = streaming)
        footer("answer").assertIsDisplayed()
        footerAction("answer", R.string.ui_branch_conversation).assertIsEnabled().performClick()
        footerAction("answer", R.string.ui_regenerate_reply_84a7d9).assertIsNotEnabled()
        footerAction("answer", R.string.ui_delete_this_conversation_3f351b).assertIsNotEnabled()
        compose.onAllNodesWithContentDescription(text(R.string.copy_answer)).assertCountEquals(0)
        assertEquals(listOf("branch:answer"), callbacks)
        compose.runOnIdle { streaming.value = false }
        footer("answer").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.work_expand)).performClick()
        assertBelow(footer("answer"), compose.onNodeWithText("Work after the answer"))
    }

    private fun showConversation(
        messages: MutableState<List<AgentChatMessageUi>>,
        streaming: MutableState<Boolean> = mutableStateOf(false),
        states: MutableMap<String, StreamingMarkdownState> = mutableStateMapOf(),
        paused: Boolean = false,
    ) {
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                CompositionLocalProvider(LocalStreamingMarkdownStates provides states) {
                    Box(Modifier.fillMaxSize()) {
                        AgentConversationMessages(
                            visibleMessages = messages.value,
                            scrollState = rememberLazyListState(),
                            isStreaming = streaming.value,
                            isPaused = paused,
                            bottomInset = 0.dp,
                            keepBottomAnchored = false,
                            onBottomAnchorChanged = {},
                            messageActionsEnabled = true,
                            branchEnabled = true,
                            onEditMessage = { callbacks += "edit:$it" },
                            onBranchMessage = { callbacks += "branch:$it" },
                            onRegenerateMessage = { callbacks += "regenerate:$it" },
                            onDeleteMessage = { callbacks += "delete:$it" },
                        )
                    }
                }
            }
        }
    }

    private fun footer(id: String) = compose.onNodeWithTag("turn-footer:$id", useUnmergedTree = true)

    private fun footerAction(id: String, resource: Int) = compose.onNode(
        hasContentDescription(text(resource)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("turn-footer:$id")),
    )

    private fun assertBelow(lower: SemanticsNodeInteraction, upper: SemanticsNodeInteraction) {
        val lowerTop = lower.fetchSemanticsNode().boundsInRoot.top
        val upperBottom = upper.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Footer must render below the complete preceding row", lowerTop >= upperBottom)
    }

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private fun tool(id: String, label: String) = ToolActivityMessageUi(
        id = id, toolName = "shell", status = ToolActivityStatusUi.Success, argumentsSummary = label,
    )
}
