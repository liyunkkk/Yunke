"""Idle trace wiring contracts; these do not prove rendered performance or timing."""
from pathlib import Path
import re
import unittest
from test_agent_chat_viewport_contract import balanced_end, code_only


ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui/components'


def body(source, name):
    found = re.search(rf'\bfun\s+{re.escape(name)}\s*\(', source)
    if found is None:
        raise AssertionError(f'Missing function {name}')
    opening = source.index('(', found.start())
    params_end = balanced_end(source, opening, '(', ')')
    start = source.index('{', params_end)
    return source[start + 1:balanced_end(source, start, '{', '}')]


class ChatScrollDiagnosticsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = code_only((ROOT / 'AgentChatBody.kt').read_text())
        cls.stream = code_only((ROOT / 'StreamPerformanceDiagnostics.kt').read_text())
        cls.helper = code_only((ROOT / 'ChatScrollDiagnostics.kt').read_text())

    def test_one_monitor_observes_the_actual_list_state_even_when_idle(self):
        chat = body(self.chat, 'AgentConversationMessages')
        self.assertEqual(len(re.findall(r'\brememberChatScrollTraceEnabled\s*\(', chat)), 1)
        self.assertEqual(len(re.findall(r'\bChatScrollMonitor\s*\(', chat)), 1)
        self.assertRegex(chat, r'ChatScrollMonitor\(state = scrollState, enabled = scrollTraceEnabled\)')
        self.assertLess(chat.index('ChatScrollMonitor('), chat.index('val timelineEntries'))
        self.assertRegex(chat, r'LazyColumn\(\s*state = scrollState,')

    def test_row_probe_is_unconditional_without_a_rendering_wrapper(self):
        chat = body(self.chat, 'AgentConversationMessages')
        self.assertEqual(len(re.findall(r'\bChatRowTrace\s*\(', chat)), 1)
        self.assertRegex(chat, r'\)\s*\{ entry ->\s*ChatRowTrace\(')
        self.assertRegex(chat, r'ChatRowTrace\(\s*rowKey = entry.key,')
        self.assertRegex(chat, r'enabled = scrollTraceEnabled,\s*\)\s*androidx.compose.runtime.CompositionLocalProvider\(')
        self.assertRegex(chat, r'key = \{ it.key \}')

    def test_idle_toggle_marker_precedes_stream_gate_without_starting_probe(self):
        toggle = body(self.stream, 'markToggle')
        self.assertLess(toggle.index('traceChatToggle(kind, expanded)'), toggle.index('if (active == null) return 0'))
        self.assertLess(toggle.index('if (active == null) return 0'), toggle.index('probeRequests.intValue++'))
        self.assertNotRegex(toggle, r'\battach\s*\(')

    def test_existing_heavy_monitor_keeps_its_original_streaming_lifecycle(self):
        monitor = body(self.stream, 'StreamPerformanceMonitor')
        self.assertIn('delay(3000)', monitor)
        self.assertRegex(monitor, r'if \(tailActive && lifecycleState.isAtLeast\(Lifecycle.State.RESUMED\) && window != null\)')
        self.assertNotIn('scrollTraceEnabled', monitor)

    def test_trace_gate_polls_only_while_resumed(self):
        gate = body(self.helper, 'rememberChatScrollTraceEnabled')
        self.assertIn('repeatOnLifecycle(Lifecycle.State.RESUMED)', gate)
        self.assertIn('enabled.value = Trace.isEnabled()', gate)
        self.assertIn('delay(CHAT_SCROLL_TRACE_POLL_MS)', gate)
        self.assertIn('enabled.value = false', gate)
        self.assertNotIn('layoutInfo', gate)
        self.assertNotIn('withFrameNanos', self.helper)

    def test_only_one_gated_geometry_collector_and_bounded_work(self):
        monitor = body(self.helper, 'ChatScrollMonitor')
        self.assertLess(monitor.index('if (!enabled) return@LaunchedEffect'), monitor.index('snapshotFlow'))
        self.assertEqual(len(re.findall(r'\bsnapshotFlow\s*\{', self.helper)), 1)
        self.assertIn('repeatOnLifecycle(Lifecycle.State.RESUMED)', monitor)
        self.assertIn('visibleItemsInfo.take(CHAT_SCROLL_TRACE_MAX_ROWS)', monitor)
        self.assertRegex(self.helper, r'CHAT_SCROLL_TRACE_MAX_ROWS = 8\b')
        self.assertRegex(self.helper, r'CHAT_SCROLL_TRACE_MAX_IDENTITIES = 256\b')
        for api in ('setMessageLogging', 'FrameMetrics', 'AndroidAgentLogger', 'graphicsLayer', 'drawWithContent'):
            self.assertNotIn(api, self.helper)
        self.assertNotRegex(self.helper, r'\bModifier\b|\.layout\s*\(')

    def test_trace_gate_does_not_key_the_row_instance_or_disposal(self):
        row = body(self.helper, 'ChatRowTrace')
        self.assertIn('val instance = remember {', row)
        self.assertIn('DisposableEffect(instance)', row)
        self.assertIn('SideEffect { instance.commit(enabled, rowKey, rowType) }', row)
        self.assertNotRegex(row, r'remember\s*\([^)]*enabled|DisposableEffect\([^)]*enabled|if\s*\(enabled\)')

    def test_toggle_does_no_work_when_platform_trace_is_disabled(self):
        toggle = body(self.helper, 'traceChatToggle')
        self.assertLess(toggle.index('if (!Trace.isEnabled()) return'), toggle.index('emitChatScrollToggle('))
