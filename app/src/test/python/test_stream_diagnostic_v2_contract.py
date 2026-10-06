"""Diagnostic-only wiring guards; runtime/collector semantics are covered by Kotlin tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'main/kotlin/io/github/mangi/eta'

class StreamDiagnosticV2Contract(unittest.TestCase):
    def test_bounded_worker_schema_and_package_lookup(self):
        source = (ROOT / 'ui/components/StreamPerformanceDiagnostics.kt').read_text()
        for kind in ('window', 'span', 'frame', 'runtime'):
            self.assertIn('v=2 type=' + kind, source)
        self.assertNotIn('BuildConfig', source)
        self.assertIn('getPackageInfo(packageName, 0)', source)
        self.assertIn('Debug.getRuntimeStats()', source)
        self.assertIn('gcTime=runtimeCounterNotPause', source)
        self.assertIn('windowStartNs=', source)
        self.assertIn('windowEndNs=', source)
        self.assertIn('(missed || total >= SPIKE_FRAME_NS) && session.details.reserveFrame()', source)
        self.assertIn('deadline > 0 && total > deadline', source)
        self.assertNotIn('spikes < SPIKE_MAX_PER_WINDOW', source)
        self.assertIn('onMessage?.invoke(started, now, isFrame)', source)
        self.assertIn('if (reserved <= NOTE_MAX_PER_SESSION) handler.post', source)

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
