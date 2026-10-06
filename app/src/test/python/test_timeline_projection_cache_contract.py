"""Cache wiring only: terminal ordering, row policy and scrolling remain authoritative."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class TimelineProjectionCacheContract(unittest.TestCase):
    def test_cache_is_used_at_the_existing_measured_projection_call(self):
        text = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        self.assertEqual(2, text.count('remember { AgentTimelineProjectionCache() }'))
        self.assertEqual(1, text.count('val timelineProjection = remember { AgentTimelineProjectionCache() }'))
        self.assertEqual(1, text.count('val standaloneTimelineProjection = remember { AgentTimelineProjectionCache() }'))
        self.assertEqual(1, text.count('timelineProjection.project(visibleMessages)'))
        self.assertEqual(1, text.count('standaloneTimelineProjection.project(visibleMessages)'))
        self.assertIn('val timelineEntries = remember(visibleMessages)', text)
        self.assertIn('measure("timeline.project", visibleMessages.size.toLong()) { timelineProjection.project(visibleMessages) }', text)

    def test_cache_has_a_reference_guard_and_the_unchanged_full_fallback(self):
        text = (ROOT / 'ui/components/AgentTimelineProjectionCache.kt').read_text()
        for token in ('it.toTimelineEntries()', 'previous.size == messages.size', 'old === current',
                      'old !is AgentMessageUi', 'current !is AgentMessageUi', 'old.id != current.id',
                      'messages.toList()', 'fullProjection(input)', 'input[sourceIndex] !== message',
                      'byId.put(message.id, index) != null', 'mapping[index] < 0'):
            self.assertIn(token, text)
        for token in ('LaunchedEffect', 'mutableStateOf', 'rememberSaveable', 'toLazyTimelineRows(',
                      'substring(', 'hashCode(', 'content =='):
            self.assertNotIn(token, text)
