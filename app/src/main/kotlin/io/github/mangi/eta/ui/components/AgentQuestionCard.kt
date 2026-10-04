package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Draft and submission state stay in the conversation store. Only whole-card disclosure is local; the form itself is never folded in parts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentQuestionCard(
    message: AgentQuestionMessageUi,
    modifier: Modifier = Modifier,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    // A recycled chat slot must not inherit another conversation/run/call/question's controls.
    key(AgentQuestionProjection.messageId(message.request)) {
        // Foundation selectable/text fields use LocalIndication. Material3 buttons use their
        // own ripple node, so both providers are required for every interaction in the card.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            WithoutPressRipple {
                AgentQuestionCardContent(message, modifier, onDraftChanged, onSubmit)
            }
        }
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
    val colors = MiuixTheme.colorScheme
    val waiting = message.status == AgentQuestionStatus.Waiting
    // Submission is guarded by the store. A local latch could stay locked when a fast failure
    // returns to the original snapshot before Compose observes the intermediate submitting state.
    // Only authoritative status transitions reset disclosure. Submitting/retry keep the form.
    var expanded by remember(message.status) { mutableStateOf(waiting) }
    var anchorBottom by remember { mutableStateOf(false) }
    val holdsBottom = LocalExpansionHoldsBottom.current
    val state = stringResource(if (expanded) R.string.question_expanded else R.string.question_collapsed)
    val statusText = questionStatusText(message.status)
    // Reuse the steps' actual outlined shell, including its sole 20dp message gutters.
    WorkProcessCardSlice(part = WorkProcessCardPart.Whole, modifier = modifier) {
        Column(Modifier.fillMaxWidth().testTag("question-card-surface")) {
            Row(Modifier.fillMaxWidth()
                .clickable(interactionSource = null, indication = null, role = Role.Button,
                    onClickLabel = stringResource(if (expanded) R.string.work_collapse else R.string.work_expand),
                    onClick = { anchorBottom = holdsBottom(); expanded = !expanded })
                .semantics { stateDescription = state }
                .heightIn(min = 48.dp).padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(15.dp),
                    tint = if (waiting) colors.primary else colors.onSurfaceVariantSummary)
                Text("$statusText · ${request.title}", modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                    contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariantSummary.copy(alpha = 0.7f))
            }
            AnimatedVisibility(visible = expanded,
                enter = tailDetailsEnter(anchorBottom), exit = tailDetailsExit(anchorBottom)) {
                Column(modifier = retainDrawLayerWhenIdle()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp).height(0.5.dp)
                        .background(colors.outline.copy(alpha = 0.45f)))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(request.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                        Text(request.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            color = colors.onSurface)
                        if (!waiting) {
                            QuestionReadOnlyDetails(message)
                        } else {
                            val editable = !message.submitting
                            val displayed = AgentQuestionProjection.draftAnswer(message)
                            val draft = AgentQuestionAnswer(message.answerKind, message.selectedOptionId, message.otherText, message.note)
                            val valid = AgentQuestionCodec.validateAnswer(request, displayed).accepted
                            val changeDraft: (AgentQuestionAnswer) -> Unit = { if (editable) onDraftChanged(it) }
                            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                request.options.forEach { option ->
                                    key(option.id) {
                                        val selected = displayed.kind == "option" && displayed.optionId == option.id
                                        QuestionOptionRow(option.label, option.description, selected, editable,
                                            option.id == request.recommendedOptionId) {
                                            changeDraft(draft.copy(kind = "option", optionId = option.id))
                                        }
                                    }
                                }
                                if (request.allowOther) {
                                    QuestionOptionRow(stringResource(R.string.question_other), "",
                                        displayed.kind == "other", editable, false) {
                                        changeDraft(draft.copy(kind = "other", optionId = null))
                                    }
                                    if (displayed.kind == "other") QuestionTextField(
                                        value = message.otherText,
                                        onValueChange = { changeDraft(draft.copy(kind = "other", optionId = null, otherText = it.take(2000))) },
                                        enabled = editable, label = stringResource(R.string.question_other_hint))
                                }
                                if (request.allowDelegation) QuestionOptionRow(stringResource(R.string.question_delegate),
                                    stringResource(R.string.question_delegate_hint), displayed.kind == "delegate", editable,
                                    false) { changeDraft(draft.copy(kind = "delegate", optionId = null)) }
                            }
                            if (request.allowNote) QuestionTextField(value = message.note,
                                onValueChange = { changeDraft(draft.copy(note = it.take(2000))) }, enabled = editable,
                                label = stringResource(R.string.question_note))
                            message.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                            Button(onClick = { if (editable && valid) onSubmit() }, enabled = editable && valid,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary, contentColor = colors.onPrimary,
                                    disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
                                    disabledContentColor = colors.onSurface.copy(alpha = 0.38f))) {
                                Text(stringResource(if (message.submitting) R.string.question_submitting else R.string.question_submit),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun questionStatusText(status: AgentQuestionStatus): String = stringResource(when (status) {
    AgentQuestionStatus.Waiting -> R.string.question_heading
    AgentQuestionStatus.Answered -> R.string.question_answered
    AgentQuestionStatus.Cancelled -> R.string.question_cancelled
    AgentQuestionStatus.Interrupted -> R.string.question_interrupted
})

@Composable
private fun QuestionOptionRow(
    label: String, description: String, selected: Boolean, enabled: Boolean, recommended: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth()
        .background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
            RoundedCornerShape(14.dp))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only the entire 48dp+ row is actionable; no competing tiny radio hit target.
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(20.dp),
            colors = RadioButtonDefaults.colors(selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariantActions,
                disabledSelectedColor = colors.primary.copy(alpha = 0.38f),
                disabledUnselectedColor = colors.onSurfaceVariantActions.copy(alpha = 0.38f)))
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            QuestionOptionLabel(label, recommended)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun QuestionOptionLabel(label: String, recommended: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MiuixTheme.colorScheme.onSurface)
        if (recommended) Text(stringResource(R.string.question_recommended),
            Modifier.background(MiuixTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MiuixTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun QuestionTextField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, label: String) {
    EtaFormTextField(value = value, onValueChange = onValueChange, enabled = enabled, hint = label,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), minLines = 1, maxLines = Int.MAX_VALUE)
}

@Composable
private fun questionSummary(message: AgentQuestionMessageUi): String? {
    if (message.status != AgentQuestionStatus.Answered) return null
    // Never turn an uncommitted draft into a historical answer. The header already shows status.
    val answer = message.answer ?: return null
    val choice = when (answer.kind) {
        "option" -> message.request.options.firstOrNull { it.id == answer.optionId }?.label ?: return null
        "other" -> stringResource(R.string.question_other) + " " + answer.otherText
        "delegate" -> stringResource(R.string.question_delegate)
        else -> return null
    }
    return stringResource(R.string.question_selected, choice)
}

/** Historical details are text, not a disabled form; they can never submit or mutate a draft. */
@Composable
private fun QuestionReadOnlyDetails(message: AgentQuestionMessageUi) {
    val request = message.request
    // Only the submitted answer is authority. Never read selectedOptionId, otherText or note drafts here.
    val answer = message.answer.takeIf { message.status == AgentQuestionStatus.Answered }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        questionSummary(message)?.let { summary ->
            // This is the only summary. Custom answers remain complete, even when very long.
            Text(summary, style = MaterialTheme.typography.bodyLarge, color = MiuixTheme.colorScheme.primary)
        }
        if (answer == null) {
            // Unanswered history may show the original options, never an uncommitted draft.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                request.options.forEach { option ->
                    QuestionHistoryOption(option.label, option.description, selected = false,
                        recommended = option.id == request.recommendedOptionId)
                }
            }
        } else {
            when (answer.kind) {
                "option" -> request.options.firstOrNull { it.id == answer.optionId }?.description
                    ?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                "delegate" -> Text(stringResource(R.string.question_delegate_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                else -> Unit
            }
        }
        answer?.note?.takeIf { it.isNotBlank() }?.let { note ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.question_note), style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun QuestionHistoryOption(label: String, description: String, selected: Boolean, recommended: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth()
        .background(if (selected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(14.dp))
        .semantics(mergeDescendants = true) { this.selected = selected }
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp),
            tint = colors.primary)
        else Box(Modifier.size(20.dp).border(1.5.dp, colors.onSurfaceVariantActions, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            QuestionOptionLabel(label, recommended)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariantSummary)
        }
    }
}
