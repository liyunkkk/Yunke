"""Diagnostic-only wiring guards; runtime/collector semantics are covered by Kotlin tests."""
from pathlib import Path
import re
import unittest
from test_agent_chat_viewport_contract import balanced_end, code_only

ROOT = Path(__file__).resolve().parents[2] / 'main/kotlin/io/github/mangi/eta'

class StreamDiagnosticV2Contract(unittest.TestCase):
    def test_bounded_worker_schema_and_package_lookup(self):
        source = (ROOT / 'ui/components/StreamPerformanceDiagnostics.kt').read_text()
        for kind in ('window', 'span', 'frame', 'runtime'):
            self.assertIn('v=2 type=' + kind, source)
        self.assertIn('BuildConfig.BUILD_TYPE', source)
        self.assertIn('BuildConfig.GIT_SHA', source)
        self.assertIn('getPackageInfo(packageName, 0)', source)
        self.assertIn('Debug.getRuntimeStats()', source)
        self.assertIn('gcTime=runtimeCounterNotPause', source)
        self.assertIn('windowStartNs=', source)
        self.assertIn('windowEndNs=', source)
        self.assertIn('(firstDraw || missed || severe) && session.details.reserveFrame(severe)', source)
        self.assertIn('deadline > 0 && total > deadline', source)
        self.assertNotIn('spikes < SPIKE_MAX_PER_WINDOW', source)
        self.assertIn('onMessage?.invoke(started, now, isFrame, coveredNs, revealNs)', source)
        code = code_only(source)
        self.assertRegex(code, r'@Synchronized\s+fun reserveNote\(\): Int\? = if \(closed\) null else notes\.reserve\(\)')
        self.assertIn('private val notes = DiagnosticNoteBudget(NOTE_MAX_PER_SESSION)', code)
        self.assertRegex(code, r'private fun enabledSession\(\): Session\?\s*\{\s*val session = active \?: return null\s*return session\.takeIf \{ StreamDiagnosticControl\.allowed && AppFileLogger\.isEnabled\(\) && !it\.closed && it\.stopCutoffNs == null \}\s*\}')
        note_start = code.index('noteSink = fun(')
        opening = code.index('{', note_start)
        note_end = balanced_end(code, opening, '{', '}')
        note = code[opening + 1:note_end]
        self.assertEqual(note.count('enabledSession()'), 1)
        self.assertEqual(note.count('session.reserveNote()'), 1)
        self.assertRegex(note, r'^\s*if \(enabledSession\(\) !== session\) return\s*val reserved = session\.reserveNote\(\) \?: return')
        # Inspect the raw slice for interpolation, but never satisfy gates from comments.
        raw_note = source[opening + 1:note_end]
        reserved = note.index('val reserved = session.reserveNote() ?: return')
        detail = raw_note.index('detail()')
        posted = note.index('handler.post {')
        self.assertLess(reserved, detail)
        self.assertLess(detail, posted)
        self.assertIn('note=$reserved', raw_note)
        self.assertNotIn('active', note)
        self.assertNotIn('NOTE_MAX_PER_SESSION', note)
        bounded = code_only((ROOT / 'ui/components/BoundedStreamDiagnostics.kt').read_text())
        budget_start = bounded.index('class DiagnosticNoteBudget(')
        budget_open = bounded.index('{', budget_start)
        budget = bounded[budget_open + 1:balanced_end(bounded, budget_open, '{', '}')]
        self.assertIn('private var accepted = 0', budget)
        self.assertIn('var dropped = 0L', budget)
        self.assertIn('require(limit > 0)', budget)
        self.assertRegex(budget, r'@Synchronized\s+fun reserve\(\): Int\?\s*\{\s*if \(accepted >= limit\) \{ dropped\+\+; return null \}\s*return \+\+accepted\s*\}')
        window_start = source.index('AppFileLogger.diagnosticInfo("$prefix v=2 type=window')
        window_end = source.index('val runtimeStats =', window_start)
        window = source[window_start:window_end]
        for field in ('noteBudgetDropped=${snapshot.noteDropped}',
                      'admission=${if (final) "closed" else "open"}',
                      'openSpansAtCutoff=${snapshot.openSpans}',
                      'closedRejectedRecords=${snapshot.closedRejectedRecords}',
                      'lateSpans=${snapshot.lateSpans}',
                      'lateAfterFinal=notTracked'):
            self.assertIn(field, window)
        self.assertIn('boundary=admissionSnapshot final=$final', source)

    def test_ipc_and_ui_attribution_wiring(self):
        runtime = (ROOT / 'agent/runtime/AgentRuntimeClient.kt').read_text()
        app = (ROOT / 'ui/app/AgentAppState.kt').read_text()
        self.assertIn('withRuntimeDiagnosticEvent(diagnosticRunId, replay = false)', runtime)
        self.assertIn('withRuntimeDiagnosticEvent(diagnosticRunId, replay = !live)', runtime)
        self.assertIn('bindRuntimeDiagnosticEvent(event)', runtime)
        self.assertIn('withRuntimeDecodedEvent(event)', runtime)
        self.assertIn('StreamUiEventDiagnostics.withEvent(runId, conversationId,', app)
        self.assertIn('StreamPerformanceDiagnostics.measure("ui.enqueue")', app)
        self.assertIn('runReplayBatch.normalize(runId, projected)', app)

if __name__ == '__main__':
    unittest.main()
