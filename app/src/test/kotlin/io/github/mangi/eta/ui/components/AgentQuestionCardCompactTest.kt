package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real card semantics, measured text and clicks. These tests do not exercise runtime transport. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentQuestionCardCompactTest {
    @get:Rule val compose = createComposeRule()
    private val message = mutableStateOf(fixture())
    private val drafts = mutableListOf<AgentQuestionAnswer>()
    private var submissions = 0

    @Test fun recommendationIsNotSelectionAndTheWholeCompactRowIsAccessible() {
        show()
        option("First choice").assertIsNotSelected().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(300.dp)
        option("Second choice").assertIsNotSelected()
        compose.onNodeWithText(text(R.string.question_recommended)).assertExists()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        // Hit the far edge, not the radio or its label.
        option("Second choice").performTouchInput { click(Offset(width - 2f, height / 2f)) }
        option("Second choice").assertIsSelected()
        option("First choice").assertIsNotSelected()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled()
        compose.runOnIdle {
            assertEquals("b", drafts.single().optionId)
            assertEquals(0, submissions)
        }
    }

    @Test fun optionalNoteStartsFoldedAndFoldingDoesNotDiscardTheDraft() {
        show()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        disclosure(text(R.string.question_note)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this condition")
        disclosure(text(R.string.question_note_added)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals("Keep this condition", message.value.note); assertEquals(0, submissions) }
        disclosure(text(R.string.question_note_added)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Keep this condition")
    }

    @Test fun otherDelegationAndNoteKeepTheirExistingAnswerSemantics() {
        show(fixture().copy(note = "Additional context"))
        option(text(R.string.question_other)).performClick()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("A custom choice")
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled()
        compose.runOnIdle {
            val answer = AgentQuestionProjection.draftAnswer(message.value)
            assertEquals(AgentQuestionAnswer("other", otherText = "A custom choice", note = "Additional context"), answer)
            assertTrue(AgentQuestionCodec.validateAnswer(message.value.request, answer).accepted)
        }
        option(text(R.string.question_delegate)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.question_delegate_hint), useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            val answer = AgentQuestionProjection.draftAnswer(message.value)
            assertEquals(AgentQuestionAnswer("delegate", note = "Additional context"), answer)
            assertTrue(AgentQuestionCodec.validateAnswer(message.value.request, answer).accepted)
            assertEquals("A custom choice", message.value.otherText) // switching kind preserves the cached draft
            assertEquals(0, submissions)
        }
        option("First choice").performClick()
        compose.runOnIdle {
            assertEquals(AgentQuestionAnswer("option", "a", note = "Additional context"),
                AgentQuestionProjection.draftAnswer(message.value))
        }
        option(text(R.string.question_other)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("A custom choice")
    }

    @Test fun disallowedOptionalAnswersDoNotCreateControls() {
        val initial = fixture()
        show(initial.copy(request = initial.request.copy(allowOther = false, allowDelegation = false, allowNote = false)))
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.question_other)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_delegate)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_note)).assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun answeredAutomaticallyBecomesOneSummaryAndExpandsIntoReadOnlyHistory() {
        show(fixture().copy(selectedOptionId = "a", note = "Uncommitted note"))
        disclosure(text(R.string.question_note_added)).performClick()
        val authoritative = AgentQuestionAnswer("option", "b", note = "Submitted condition")
        compose.runOnIdle { message.value = message.value.copy(status = AgentQuestionStatus.Answered, answer = authoritative) }
        val summary = text(R.string.question_selected, "Second choice")
        disclosure(summary).assertExists().assertHeightIsAtLeast(48.dp)
        assertCompactSummary()
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        assertNoForm()
        disclosure(summary).performClick()
        compose.onNodeWithText("Decision title").assertExists()
        compose.onNodeWithText("Choose the next step.").assertExists()
        compose.onNodeWithText("Second description").assertExists()
        compose.onNodeWithText("Submitted condition").assertExists()
        compose.onNodeWithText("Uncommitted note").assertDoesNotExist()
        assertNoForm()
        disclosure(summary).performClick()
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submissions); assertTrue(drafts.isEmpty()) }
    }

    @Test fun missingHistoricalAnswerNeverFallsBackToRestoredDraftFields() {
        show(fixture().copy(status = AgentQuestionStatus.Answered, answer = null,
            selectedOptionId = "a", answerKind = "other", otherText = "Unsent custom choice", note = "Unsent note"))
        disclosure(text(R.string.question_answered)).assertExists().performClick()
        compose.onNodeWithText("Decision title").assertExists()
        compose.onNodeWithText("Unsent custom choice", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Unsent note").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_selected, "First choice")).assertDoesNotExist()
        assertNoForm()
        compose.runOnIdle { assertTrue(drafts.isEmpty()); assertEquals(0, submissions) }
    }

    @Test fun cancelledAndInterruptedAlsoCollapseAndNeverPresentADraftAsAnAnswer() {
        show(fixture().copy(selectedOptionId = "a", note = "Unsent condition"))
        disclosure(text(R.string.question_note_added)).performClick()
        for ((status, label) in listOf(AgentQuestionStatus.Cancelled to R.string.question_cancelled,
            AgentQuestionStatus.Interrupted to R.string.question_interrupted)) {
            compose.runOnIdle { message.value = message.value.copy(status = status) }
            disclosure(text(label)).assertExists()
            assertCompactSummary()
            compose.onNodeWithText("Decision title").assertDoesNotExist()
            assertNoForm()
            disclosure(text(label)).performClick()
            compose.onNodeWithText("Decision title").assertExists()
            compose.onNodeWithText("Unsent condition").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.question_selected, "First choice")).assertDoesNotExist()
            assertNoForm()
        }
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun historicalOtherAndDelegationAnswersAreReadableWithoutInputFields() {
        show(fixture().copy(status = AgentQuestionStatus.Answered,
            answer = AgentQuestionAnswer("other", otherText = "Custom answer", note = "Custom note")))
        val otherSummary = text(R.string.question_selected, text(R.string.question_other) + " Custom answer")
        disclosure(otherSummary).performClick()
        compose.onNodeWithText("Custom note").assertExists()
        assertNoForm()
        disclosure(otherSummary).performClick()
        compose.runOnIdle { message.value = message.value.copy(answer = AgentQuestionAnswer("delegate")) }
        val delegateSummary = text(R.string.question_selected, text(R.string.question_delegate))
        disclosure(delegateSummary).performClick()
        compose.onNodeWithText(text(R.string.question_delegate_hint)).assertExists()
        assertNoForm()
    }

    @Test fun longTextCanBeFullyRevealedAndTheSelectedDescriptionAndDelegationScopeAreNeverClipped() {
        val initial = fixture()
        val body = (1..9).joinToString("\n") { "Question condition $it" }
        val description = (1..5).joinToString("\n") { "Option condition $it" }
        show(initial.copy(request = initial.request.copy(question = body,
            options = listOf(initial.request.options[0].copy(description = description), initial.request.options[1]))))
        assertTrue(layout(body).hasVisualOverflow)
        assertEquals(3, layout(body).lineCount)
        assertTrue(layout(description).hasVisualOverflow)
        assertEquals(2, layout(description).lineCount)
        assertFalse(layout(text(R.string.question_delegate_hint)).hasVisualOverflow)
        disclosure(text(R.string.question_show_full_text)).performClick()
        assertFalse(layout(body).hasVisualOverflow)
        assertEquals(9, layout(body).lineCount)
        assertFalse(layout(description).hasVisualOverflow)
        assertEquals(5, layout(description).lineCount)
        disclosure(text(R.string.question_show_less)).performClick()
        assertTrue(layout(body).hasVisualOverflow)
        option("First choice").performClick()
        assertFalse(layout(description).hasVisualOverflow)
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun localDisclosureIsIsolatedByEveryQuestionIdentityFieldNotMessageId() {
        val initial = fixture()
        val request = initial.request.copy(question = (1..7).joinToString("\n") { "Condition $it" })
        show(initial.copy(request = request, selectedOptionId = "a"), updateSubmitting = false)
        // Deliberately reuse both the composition slot and UI message id, changing one owner field at a time.
        for (next in listOf(request.copy(conversationId = "other-conversation"), request.copy(runId = "other-run"),
            request.copy(toolCallId = "other-call"), request.copy(questionId = "other-question"))) {
            // Always start from the same owner so each transition changes exactly one identity field.
            compose.runOnIdle { message.value = initial.copy(request = request, selectedOptionId = "a") }
            disclosure(text(R.string.question_show_full_text)).performClick()
            disclosure(text(R.string.question_note)).performClick()
            compose.onNodeWithText(text(R.string.question_submit)).performClick()
            compose.runOnIdle { message.value = initial.copy(request = next, selectedOptionId = "a") }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            assertTrue(layout(request.question).hasVisualOverflow)
            compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled()
            disclosure(text(R.string.question_show_full_text)).assertExists()
        }
        compose.runOnIdle { assertEquals(4, submissions) }
    }

    @Test fun expandedTerminalDetailsDoNotLeakIntoAnotherOwnerOrAnotherTerminalStatus() {
        show(fixture().copy(status = AgentQuestionStatus.Cancelled))
        disclosure(text(R.string.question_cancelled)).performClick()
        compose.onNodeWithText("Decision title").assertExists()
        compose.runOnIdle {
            message.value = message.value.copy(request = message.value.request.copy(toolCallId = "another-call"))
        }
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        disclosure(text(R.string.question_cancelled)).performClick()
        compose.runOnIdle {
            message.value = message.value.copy(status = AgentQuestionStatus.Answered, answer = AgentQuestionAnswer("option", "b"))
        }
        disclosure(text(R.string.question_selected, "Second choice")).assertExists()
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        assertNoForm()
    }

    @Test fun submittingDisablesTheFormAndExplicitRetryIsPossibleAfterRejection() {
        show(fixture().copy(selectedOptionId = "a"))
        compose.onNodeWithText(text(R.string.question_submit)).performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        compose.onNodeWithText(text(R.string.question_submitting)).assertIsNotEnabled()
        // Accepted submission is not an authoritative Answered event: keep the question visible.
        compose.onNodeWithText("Decision title").assertExists()
        compose.onNodeWithText("Choose the next step.").assertExists()
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(4)
        option("Second choice").assertIsNotEnabled()
        compose.runOnIdle { message.value = message.value.copy(submitting = false, error = "Try again") }
        compose.onNodeWithText("Try again").assertExists()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, submissions) }
    }

    @Test fun coalescedFastFailureWithUnchangedErrorDoesNotLatchTheForm() {
        show(fixture().copy(selectedOptionId = "a", error = "Same failure"), updateSubmitting = false)
        val click = compose.onNodeWithText(text(R.string.question_submit)).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        // Both store transitions finish before Compose can observe the intermediate state.
        compose.runOnIdle {
            click()
            val before = message.value
            message.value = before.copy(submitting = true, error = null)
            message.value = before
        }
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled().performClick()
        option("Second choice").assertIsEnabled()
        compose.runOnIdle { assertEquals(2, submissions) }
    }

    private fun show(initial: AgentQuestionMessageUi = fixture(), updateSubmitting: Boolean = true) {
        message.value = initial
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(340.dp).verticalScroll(rememberScrollState())) {
                    AgentQuestionCard(message.value, Modifier.testTag(CARD), onDraftChanged = { answer ->
                        drafts += answer
                        message.value = message.value.copy(answerKind = answer.kind, selectedOptionId = answer.optionId,
                            otherText = answer.otherText, note = answer.note)
                    }, onSubmit = {
                        submissions++
                        if (updateSubmitting) message.value = message.value.copy(submitting = true)
                    })
                }
            }
        }
    }

    private fun assertCompactSummary() {
        val height = compose.onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot.height
        assertTrue("A terminal card should be a single compact row", height <= with(compose.density) { 64.dp.toPx() })
    }

    private fun assertNoForm() {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.question_submit)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_submitting)).assertDoesNotExist()
    }

    private fun role(value: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, value)
    private fun option(label: String) = compose.onNode(hasText(label) and role(Role.RadioButton))
    private fun disclosure(label: String) = compose.onNode(hasText(label) and role(Role.Button))
    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(results))
        }
        return results.single()
    }

    private fun fixture() = AgentQuestionMessageUi("same-ui-slot", AgentQuestionRequest(
        questionId = "question", conversationId = "conversation", runId = "run", toolCallId = "call",
        title = "Decision title", question = "Choose the next step.",
        options = listOf(AgentQuestionOption("a", "First choice", "First description"),
            AgentQuestionOption("b", "Second choice", "Second description")), recommendedOptionId = "a"))

    private companion object { const val CARD = "compact-question-card" }
}
