package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi

/** Draft and submission state stay in the conversation store; only disclosure is local. */
@Composable
internal fun AgentQuestionCard(
    message: AgentQuestionMessageUi,
    modifier: Modifier = Modifier,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    // A recycled chat slot must not inherit another conversation/run/call/question's UI state.
    key(AgentQuestionProjection.messageId(message.request)) {
        AgentQuestionCardContent(message, modifier, onDraftChanged, onSubmit)
    }
}

@Composable
private fun AgentQuestionCardContent(
    message: AgentQuestionMessageUi,
    modifier: Modifier,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    val request = message.request
    val waiting = message.status == AgentQuestionStatus.Waiting
    // Every terminal transition collapses immediately, without waiting for an effect/frame.
    var detailsExpanded by remember(message.status) { mutableStateOf(false) }
    // Submission is guarded by the store. A local latch could stay locked when a fast failure
    // returns to the original snapshot before Compose observes the intermediate submitting state.
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!waiting) {
                val summary = questionSummary(message)
                QuestionDisclosureRow(summary, detailsExpanded,
                    stringResource(R.string.question_view_details), stringResource(R.string.question_hide_details),
                    singleLine = true) { detailsExpanded = !detailsExpanded }
                if (detailsExpanded) QuestionReadOnlyDetails(message, summary)
            } else {
                val editable = !message.submitting
                val displayed = AgentQuestionProjection.draftAnswer(message)
                val draft = AgentQuestionAnswer(message.answerKind, message.selectedOptionId, message.otherText, message.note)
                val valid = AgentQuestionCodec.validateAnswer(request, displayed).accepted
                val changeDraft: (AgentQuestionAnswer) -> Unit = { if (editable) onDraftChanged(it) }
                var fullTextExpanded by remember(request.question, request.options) { mutableStateOf(false) }
                val overflows = remember(request.question, request.options) { mutableStateMapOf<String, Boolean>() }
                var noteExpanded by remember { mutableStateOf(false) }

                Text(stringResource(R.string.question_heading), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(request.title, style = MaterialTheme.typography.titleSmall)
                QuestionDescription(request.question, fullTextExpanded, 3, MaterialTheme.typography.bodySmall) {
                    overflows["question"] = it
                }
                // One disclosure for long text, not an extra button on every option. Keep the
                // original strings intact (also in semantics), and never truncate delegation scope.
                if (overflows.values.any { it }) QuestionDisclosureRow(
                    stringResource(if (fullTextExpanded) R.string.question_show_less else R.string.question_show_full_text),
                    fullTextExpanded) { fullTextExpanded = !fullTextExpanded }

                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    request.options.forEach { option ->
                        key(option.id) {
                            val selected = displayed.kind == "option" && displayed.optionId == option.id
                            QuestionOptionRow(option.label, option.description, selected, editable,
                                option.id == request.recommendedOptionId, fullTextExpanded || selected,
                                onOverflow = { overflows["option:${option.id}"] = it }) {
                                changeDraft(draft.copy(kind = "option", optionId = option.id))
                            }
                        }
                    }
                    if (request.allowOther) {
                        QuestionOptionRow(stringResource(R.string.question_other), "",
                            displayed.kind == "other", editable, false, true) {
                            changeDraft(draft.copy(kind = "other", optionId = null))
                        }
                        if (displayed.kind == "other") OutlinedTextField(
                            value = message.otherText,
                            onValueChange = { changeDraft(draft.copy(kind = "other", optionId = null, otherText = it.take(2000))) },
                            enabled = editable, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.question_other_hint)) }, maxLines = 3)
                    }
                    if (request.allowDelegation) QuestionOptionRow(stringResource(R.string.question_delegate),
                        stringResource(R.string.question_delegate_hint), displayed.kind == "delegate", editable,
                        false, true) { changeDraft(draft.copy(kind = "delegate", optionId = null)) }
                }
                if (request.allowNote) {
                    QuestionDisclosureRow(stringResource(if (message.note.isBlank()) R.string.question_note else R.string.question_note_added),
                        noteExpanded) { noteExpanded = !noteExpanded }
                    if (noteExpanded) OutlinedTextField(value = message.note,
                        onValueChange = { changeDraft(draft.copy(note = it.take(2000))) }, enabled = editable,
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.question_note)) }, maxLines = 3)
                }
                message.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    if (editable && valid) onSubmit()
                }, enabled = editable && valid, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (message.submitting) R.string.question_submitting else R.string.question_submit))
                }
            }
        }
    }
}

@Composable
private fun QuestionOptionRow(
    label: String, description: String, selected: Boolean, enabled: Boolean, recommended: Boolean,
    expanded: Boolean, onOverflow: (Boolean) -> Unit = {}, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
            RoundedCornerShape(8.dp))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Only the entire 48dp+ row is actionable; no competing tiny radio hit target.
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotBlank()) QuestionDescription(description, expanded, 2,
                MaterialTheme.typography.labelSmall, onOverflow)
        }
        if (recommended) Text(stringResource(R.string.question_recommended), Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QuestionDescription(
    text: String, expanded: Boolean, previewLines: Int, style: TextStyle, onOverflow: (Boolean) -> Unit,
) {
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else previewLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) onOverflow(it.hasVisualOverflow) })
}

@Composable
private fun QuestionDisclosureRow(
    label: String, expanded: Boolean,
    expandLabel: String = label, collapseLabel: String = label,
    singleLine: Boolean = false, onClick: () -> Unit,
) {
    val state = stringResource(if (expanded) R.string.question_expanded else R.string.question_collapsed)
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
        onClickLabel = if (expanded) collapseLabel else expandLabel, onClick = onClick)
        .semantics { stateDescription = state }.heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun questionSummary(message: AgentQuestionMessageUi): String {
    if (message.status != AgentQuestionStatus.Answered) return stringResource(when (message.status) {
        AgentQuestionStatus.Cancelled -> R.string.question_cancelled
        AgentQuestionStatus.Interrupted -> R.string.question_interrupted
        else -> R.string.question_waiting
    })
    // Never turn an uncommitted draft into a historical answer.
    val answer = message.answer ?: return stringResource(R.string.question_answered)
    val choice = when (answer.kind) {
        "option" -> message.request.options.firstOrNull { it.id == answer.optionId }?.label
            ?: return stringResource(R.string.question_answered)
        "other" -> stringResource(R.string.question_other) + " " + answer.otherText
        "delegate" -> stringResource(R.string.question_delegate)
        else -> return stringResource(R.string.question_answered)
    }
    return stringResource(R.string.question_selected, choice)
}

/** Historical details are text, not a disabled form; expanding can never submit or mutate a draft. */
@Composable
private fun QuestionReadOnlyDetails(message: AgentQuestionMessageUi, summary: String) {
    val request = message.request
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(request.title, style = MaterialTheme.typography.titleSmall)
        Text(request.question, style = MaterialTheme.typography.bodySmall)
        request.options.forEach { option ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (option.id == request.recommendedOptionId) Text(stringResource(R.string.question_recommended),
                        Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (request.allowOther) Text(stringResource(R.string.question_other), style = MaterialTheme.typography.bodyMedium)
        if (request.allowDelegation) {
            Text(stringResource(R.string.question_delegate), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.question_delegate_hint), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (message.status == AgentQuestionStatus.Answered) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            message.answer?.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(stringResource(R.string.question_note), style = MaterialTheme.typography.labelSmall)
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
