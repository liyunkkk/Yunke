"""Source wiring guards only: no Kotlin compile, real-device rendering or ripple capture."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class QuestionMessageLayoutContractTest(unittest.TestCase):
    def setUp(self):
        self.card = (SOURCE / 'AgentQuestionCard.kt').read_text()
        self.entry = (SOURCE / 'ChatMessageItem.kt').read_text()
        self.shell = (SOURCE / 'AgentWorkProcessCard.kt').read_text()

    def test_actual_steps_shell_owns_the_single_message_gutter(self):
        self.assertIn('WorkProcessCardSlice(part = WorkProcessCardPart.Whole, modifier = modifier)', self.card)
        self.assertIn('start = 20.dp', self.shell)
        self.assertIn('end = 20.dp', self.shell)
        self.assertNotIn('Surface(', self.card)
        self.assertIn('question-card-surface', self.card)
        branch = self.entry.split('is io.github.mangi.eta.ui.model.AgentQuestionMessageUi ->', 1)[1].split('is UserMessageUi ->', 1)[0]
        self.assertNotIn('padding(', branch)  # no duplicated gutter
        self.assertIn('actions.onQuestionDraftChanged(message.request.conversationId, message.request.questionId, draft)', branch)
        self.assertIn('actions.onSubmitQuestionAnswer(message.request.conversationId, message.request.questionId)', branch)

    def test_whole_card_uses_existing_step_animation_and_click_time_anchor(self):
        self.assertEqual(1, self.card.count('AnimatedVisibility('))
        self.assertIn('enter = tailDetailsEnter(anchorBottom), exit = tailDetailsExit(anchorBottom)', self.card)
        self.assertIn('Column(modifier = retainDrawLayerWhenIdle())', self.card)
        parent = (SOURCE / 'AgentChatBody.kt').read_text()
        provider = parent.index('LocalExpansionHoldsBottom provides expansionHoldsBottom')
        entry = parent.index('is AgentTimelineRow.Message -> {', provider)
        self.assertIn('ChatMessageItem(', parent[entry:entry + 2500])
        self.assertIn('val holdsBottom = LocalExpansionHoldsBottom.current', self.card)
        self.assertEqual(1, self.card.count('holdsBottom()'))
        self.assertIn('onClick = { anchorBottom = holdsBottom(); expanded = !expanded }', self.card)
        self.assertIn('Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight', self.card)
        self.assertNotIn('tween(', self.card)  # do not invent a similar but different animation

    def test_authoritative_status_not_submit_latch_controls_default_disclosure(self):
        self.assertIn('key(AgentQuestionProjection.messageId(message.request))', self.card)
        self.assertIn('var expanded by remember(message.status) { mutableStateOf(waiting) }', self.card)
        self.assertNotIn('remember(message.submitting)', self.card)
        submit = self.card.split('Button(onClick =', 1)[1].split('}, enabled =', 1)[0]
        self.assertIn('if (editable && valid) onSubmit()', submit)
        self.assertNotIn('expanded', submit)
        self.assertIn('val editable = !message.submitting', self.card)
        self.assertIn('AgentQuestionCodec.validateAnswer(request, displayed).accepted', self.card)

    def test_no_nested_disclosures_or_clipped_body_and_note(self):
        for obsolete in ('detailsExpanded', 'fullTextExpanded', 'noteExpanded', 'QuestionDisclosureRow',
                         'QuestionDescription', 'question_show_full_text', 'question_note_added'):
            self.assertNotIn(obsolete, self.card)
        body = self.card.split('AnimatedVisibility(visible = expanded,', 1)[1]
        self.assertNotIn('TextOverflow.Ellipsis', body)
        self.assertIn('maxLines = Int.MAX_VALUE', body)
        self.assertIn('MaterialTheme.typography.bodyLarge', body)
        self.assertIn('MaterialTheme.typography.bodyMedium', body)

    def test_foundation_material_and_header_clicks_have_no_ripple(self):
        self.assertIn('LocalRippleConfiguration provides null', self.card)
        self.assertIn('WithoutPressRipple {', self.card)
        self.assertIn('.clickable(interactionSource = null, indication = null, role = Role.Button', self.card)
        helper = (SOURCE / 'NoRippleIndication.kt').read_text()
        self.assertIn('LocalIndication provides NoRippleIndication', helper)
        self.assertIn('object : Modifier.Node() {}', helper)

    def test_history_uses_only_authoritative_answer_and_one_summary(self):
        self.assertIn('message.answer.takeIf { message.status == AgentQuestionStatus.Answered }', self.card)
        history = self.card.split('private fun QuestionReadOnlyDetails', 1)[1].split('private fun QuestionHistoryOption', 1)[0]
        for draft in ('message.selectedOptionId', 'message.otherText', 'message.note'):
            self.assertNotIn(draft, history)
        self.assertEqual(1, self.card.count('questionSummary(message)?.let'))
        self.assertIn('if (answer == null)', history)
        self.assertIn('answer?.note?.takeIf', history)
        self.assertNotIn('onDraftChanged', history)
        self.assertNotIn('onSubmit', history)

    def test_optional_controls_still_respect_request_capabilities(self):
        self.assertIn('if (request.allowOther)', self.card)
        self.assertIn('if (request.allowDelegation)', self.card)
        self.assertIn('if (request.allowNote) QuestionTextField', self.card)
        self.assertIn('onClick = null', self.card)  # radio's parent owns the accessible full row
        self.assertIn('role = Role.RadioButton', self.card)
        self.assertIn('.heightIn(min = 48.dp)', self.card)
        self.assertIn('option.id == request.recommendedOptionId', self.card)
        self.assertIn('displayed.kind == "option" && displayed.optionId == option.id', self.card)
        self.assertNotRegex(self.card, r'selectedOptionId\s*=\s*request.recommendedOptionId')

    def test_monet_semantic_colors_and_whole_card_semantics(self):
        self.assertIn('MiuixTheme.colorScheme', self.card)
        self.assertIn('stateDescription = state', self.card)
        self.assertIn('R.string.work_collapse else R.string.work_expand', self.card)
        self.assertNotRegex(self.card, r'Color\(0x')
        self.assertNotIn('tonalElevation', self.card)


if __name__ == '__main__':
    unittest.main()
