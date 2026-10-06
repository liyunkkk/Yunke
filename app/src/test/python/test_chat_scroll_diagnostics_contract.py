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


def class_body(source, name):
    found = re.search(rf'\bclass\s+{re.escape(name)}\b', source)
    if found is None:
        raise AssertionError(f'Missing class {name}')
    start = source.index('{', found.end())
    return source[start + 1:balanced_end(source, start, '{', '}')]


class ChatScrollDiagnosticsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = code_only((ROOT / 'AgentChatBody.kt').read_text())
        cls.stream = code_only((ROOT / 'StreamPerformanceDiagnostics.kt').read_text())
        cls.bounded = code_only((ROOT / 'BoundedStreamDiagnostics.kt').read_text())
        cls.helper = code_only((ROOT / 'ChatScrollDiagnostics.kt').read_text())

    def test_one_monitor_observes_the_actual_list_state_even_when_idle(self):
        chat = body(self.chat, 'AgentConversationMessages')
        self.assertEqual(len(re.findall(r'\brememberChatScrollTraceEnabled\s*\(', chat)), 1)
        self.assertEqual(len(re.findall(r'\bChatScrollMonitor\s*\(', chat)), 1)
        self.assertRegex(chat, r'ChatScrollMonitor\(state = scrollState, enabled = scrollTraceEnabled\)')
        self.assertLess(chat.index('ChatScrollMonitor('), chat.index('val projectedTimelineEntries'))
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
        self.assertLess(toggle.index('traceChatToggle(kind, expanded)'), toggle.index('if (!enabled) return 0'))
        self.assertLess(toggle.index('if (!enabled) return 0'), toggle.index('probeRequests.intValue++'))
        self.assertNotRegex(toggle, r'\battach\s*\(')

    def test_android_sink_rechecks_live_off_gate_without_unbalanced_sections(self):
        counter = (ROOT / 'ChatScrollDiagnostics.kt').read_text().split('private object AndroidChatScrollTraceSink', 1)[1]
        self.assertEqual(counter.count('if (!StreamDiagnosticControl.allowed || !Trace.isEnabled()) return'), 2)
        self.assertLess(counter.index('if (!StreamDiagnosticControl.allowed'), counter.index('Trace.setCounter'))
        section = counter.split('override fun section', 1)[1].split('private val chatScrollTraceIds', 1)[0]
        self.assertLess(section.index('if (!StreamDiagnosticControl.allowed'), section.index('Trace.beginSection'))
        self.assertEqual(section.count('Trace.beginSection'), 1)
        self.assertEqual(section.count('Trace.endSection'), 1)

    def test_window_monitor_requires_opt_in_and_foreground_not_streaming(self):
        # Page-level jank diagnostics are explicitly enabled by the global log switch.
        monitor = body(self.stream, 'StreamPerformanceMonitor')
        self.assertIn('lifecycleState.isAtLeast(Lifecycle.State.RESUMED)', monitor)
        self.assertIn('if (loggingEnabled && diagnosticAllowed && resumed && window != null)', monitor)
        self.assertIn('DisposableEffect(view, loggingEnabled, resumed, pages, diagnosticAllowed)', monitor)
        self.assertIn('onDispose { detach?.invoke() }', monitor)
        self.assertNotIn('isStreaming', monitor)
        self.assertNotIn('scrollTraceEnabled', monitor)
        root = (ROOT.parent / 'app/AgentAppRoot.kt').read_text()
        self.assertEqual(root.count('StreamPerformanceMonitor('), 1)
        self.assertIn('SettingsDataStore.fileLoggingEnabledFlow()', root)
        self.assertNotIn('StreamPerformanceMonitor(', self.chat)

    def test_frame_attribution_uses_frame_time_and_bounded_periodic_reporting(self):
        attach = body(self.stream, 'attach')
        self.assertIn('FrameMetrics.INTENDED_VSYNC_TIMESTAMP', attach)
        self.assertIn('pages.attributeFrame(intended, total)', attach)
        self.assertIn('page.aggregatePage.frameStage', attach)
        self.assertIn('${page.fields()}', (ROOT / 'StreamPerformanceDiagnostics.kt').read_text())
        self.assertIn('snapshot.rawDetails.select()', attach)
        self.assertIn('session.details.reserveFrame(severe)', attach)
        self.assertIn('handler.postDelayed(this, 5000)', attach)
        self.assertIn('active === session && enabled', attach)

    def test_session_snapshot_detaches_all_windows_under_the_shared_admission_lock(self):
        session = class_body(self.stream, 'Session')
        self.assertIn('BoundedDiagnosticDetails(started, admissionLock = this)', session)
        for name in ('recordMetric', 'finishSpan', 'snapshot'):
            self.assertRegex(session, rf'@Synchronized\s+fun\s+{name}\s*\(')
        snapshot = body(session, 'snapshot')
        # Stop fixes the logical window end for all remaining snapshots: an in-flight
        # periodic emit must not move it beyond the final cutoff (backward window).
        self.assertIn('val raw = details.snapshot(stopCutoffNs ?: clock(), final)', snapshot)
        self.assertIn('if (final) closed = true', snapshot)
        self.assertRegex(snapshot, r'SessionSnapshot\(raw,\s*stats,\s*pageStats,\s*droppedStageRecords,\s*invalidLabels,')
        # The maps in the snapshot must be detached, not cleared in place.
        steps = ('details.snapshot(stopCutoffNs ?: clock(), final)', 'if (final) closed = true',
                 'val result = SessionSnapshot(', 'stats = linkedMapOf()',
                 'pageStats = linkedMapOf()', 'droppedStageRecords = 0',
                 'invalidLabels = 0', 'return result')
        positions = [snapshot.index(step) for step in steps]
        self.assertEqual(positions, sorted(positions))
        self.assertNotRegex(snapshot, r'\.(?:clear|select|report)\s*\(|diagnosticInfo')

        bounded = class_body(self.bounded, 'BoundedDiagnosticDetails')
        self.assertRegex(bounded, r'fun\s+snapshot\([^)]*\)[^{]*=\s*synchronized\(admissionLock\)\s*\{')
        raw = body(bounded, 'snapshot')
        for column in ('stages', 'spans', 'parents', 'begins', 'ends', 'threads',
                       'mains', 'attrs', 'pages', 'pageEnds', 'values'):
            self.assertIn(f'{column}.copyOf()', raw)
        self.assertIn('frames.copyOf(frameSize)', raw)
        self.assertLess(raw.index('val raw = DiagnosticRawDetailSnapshot('), raw.index('stages.fill(null)'))
        self.assertIn('if (final) closed = true', raw)
        self.assertNotRegex(raw, r'\.select\s*\(|DiagnosticSpanRecord\s*\(|diagnosticInfo')
        selection = class_body(self.bounded, 'DiagnosticRawDetailSnapshot')
        self.assertNotRegex(selection, r'@Synchronized|\bsynchronized\s*\(')
        self.assertIn('candidates(includeProtected = true)', body(selection, 'select'))
        self.assertIn('DiagnosticSpanRecord(', body(selection, 'candidates'))

    def test_emit_selects_and_reports_the_same_snapshot_outside_the_lock(self):
        attach = body(self.stream, 'attach')
        self.assertNotIn('session.details.drain(', attach)
        self.assertNotRegex(attach, r'@Synchronized\s+fun\s+emit\s*\(')
        emit = body(attach, 'emit')
        self.assertIn('val snapshot = closedSnapshot ?: run {', emit)
        # snapshot owns the lock itself; the observer timer starts BEFORE acquiring it.
        self.assertNotRegex(emit, r'\bsynchronized\s*\(')
        lock_end = emit.index('session.snapshot(final)')
        self.assertLess(emit.index('Phase.Snapshot'), lock_end)
        selected = emit.index('val detail = snapshot.rawDetails.select()')
        reported = emit.index('snapshot.report(session.id, session.started, final)')
        self.assertLess(lock_end, selected)
        self.assertLess(selected, reported)
        self.assertNotIn('session.report(', emit)

    def test_detach_stops_new_admission_then_drains_original_worker_before_final_completion(self):
        attach = body(self.stream, 'attach')
        stop = attach[attach.index('val stop:'):]
        steps = ('session.stopAdmission()', 'window.removeOnFrameMetricsAvailableListener(listener)',
                 'publishSession(null)', 'log.closeOpen(cutoff)', 'handler.removeCallbacks(periodic)',
                 'val drainUntil =', 'val emitted = emit(true)', 'AppFileLogger.diagnosticCompletion(',
                 'completion.complete(', 'thread.quitSafely()')
        positions = [stop.index(step) for step in steps]
        self.assertEqual(positions, sorted(positions))
        self.assertIn('250_000_000L', stop)
        self.assertIn('handler.postDelayed(this, 10)', stop)
        self.assertNotRegex(stop, r'Thread\.sleep|\.await\(|completion\.get\(')
        self.assertIn('return { stop(); Unit }', stop)
        self.assertNotIn('active === session && enabled &&', attach)
        self.assertIn('session.admitFrame(intendedFrame, AppFileLogger.isEnabled())', attach)

    def test_trace_gate_polls_only_while_resumed(self):
        gate = body(self.helper, 'rememberChatScrollTraceEnabled')
        self.assertIn('repeatOnLifecycle(Lifecycle.State.RESUMED)', gate)
        self.assertIn('enabled.value = StreamDiagnosticControl.allowed && Trace.isEnabled()', gate)
        self.assertIn('if (!allowed)', gate)
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
        self.assertLess(toggle.index('if (!StreamDiagnosticControl.allowed || !Trace.isEnabled()) return'), toggle.index('emitChatScrollToggle('))
