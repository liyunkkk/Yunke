"""Detailed Compose tracing is observational, opt-in, and installed before UI creation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'

class ComposeSystemTraceContract(unittest.TestCase):
    def test_startup_installs_once_after_logger(self):
        app = (SRC / 'EtaApp.kt').read_text()
        self.assertEqual(app.count('ComposeSystemTrace.install()'), 1)
        self.assertLess(app.index('AppFileLogger.install(this)'), app.index('ComposeSystemTrace.install()'))
        self.assertLess(app.index('shouldInitializeFullRuntime(Application.getProcessName(), packageName)'), app.index('ComposeSystemTrace.install()'))

    def test_off_override_is_read_before_logger_and_tracer_with_fail_closed_startup(self):
        app = (SRC / 'EtaApp.kt').read_text()
        control = (SRC / 'ui/components/StreamDiagnosticControl.kt').read_text()
        self.assertIn('@Volatile var allowed = false', control)
        self.assertIn('internal fun initialize(context: Context)', control)
        self.assertIn('.getOrDefault("1")', control)
        self.assertEqual(app.count('StreamDiagnosticControl.initialize(this@EtaApp)'), 1)
        self.assertLess(app.index('StreamDiagnosticControl.initialize(this@EtaApp)'), app.index('AppFileLogger.install(this)'))
        self.assertLess(app.index('StreamDiagnosticControl.initialize(this@EtaApp)'), app.index('ComposeSystemTrace.install()'))
        self.assertLess(app.index('shouldInitializeFullRuntime(Application.getProcessName(), packageName)'), app.index('StreamDiagnosticControl.initialize(this@EtaApp)'))

    def test_tracer_is_opt_in_and_has_no_composition_or_disk_side_effects(self):
        trace = (SRC / 'ui/components/ComposeSystemTrace.kt').read_text()
        self.assertIn('AppFileLogger.isEnabled() && Trace.isEnabled()', trace)
        self.assertIn('Thread.currentThread() === owner && (depth > 0 || enabled())', trace)
        self.assertIn('Composer.setTracer(', trace)
        self.assertIn('Trace::beginSection', trace)
        self.assertIn('Trace::endSection', trace)
        for forbidden in ('@Composable', 'mutableStateOf(', 'LaunchedEffect(', 'Thread.sleep(', 'AppFileLogger.info(', 'diagnosticInfo(', 'getStackTrace(', 'Throwable('):
            self.assertNotIn(forbidden, trace)

    def test_capture_streams_to_disk_with_finite_duration_and_size(self):
        cfg = (ROOT / 'docs/diagnostics/eta-jank.perfetto').read_text()
        for required in ('write_into_file: true', 'file_write_period_ms: 500', 'duration_ms: 45000', 'max_file_size_bytes: 536870912', 'atrace_apps: "io.github.mangi.eta"', 'android.surfaceflinger.frametimeline'):
            self.assertIn(required, cfg)

    def test_release_rules_do_not_remove_compiler_trace_hooks(self):
        self.assertIn('includeTraceMarkers = true', (ROOT / 'app/build.gradle.kts').read_text())
        rules = (ROOT / 'app/proguard-rules.pro').read_text()
        self.assertNotIn('traceEventStart', rules)
        self.assertNotIn('traceEventEnd', rules)
        self.assertNotIn('isTraceInProgress', rules)
