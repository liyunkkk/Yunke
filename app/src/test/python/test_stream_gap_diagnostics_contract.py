"""Narrow source contracts only; no Android compilation, execution or timing claims."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
COMPONENTS = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/components"


class StreamGapDiagnosticsContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stream = (COMPONENTS / "StreamPerformanceDiagnostics.kt").read_text()
        cls.bounded = (COMPONENTS / "BoundedStreamDiagnostics.kt").read_text()
        cls.modifier = (COMPONENTS / "StreamDiagnosticModifier.kt").read_text()
        cls.body = (COMPONENTS / "ChatBodyTrace.kt").read_text()

    def test_new_labels_are_registered_only_as_central_literals(self):
        registry = self.stream.split("internal object StreamDiagnosticGapLabels {", 1)[1].split("\n}", 1)[0]
        expected = {"main.uninstrumented", "main.nonReveal", "chat.content.commit", "list.measure", "list.place", "row.measure", "row.place", "row.draw", "settings.section.measure", "settings.section.draw"}
        self.assertEqual(set(re.findall(r'"([a-zA-Z.]+)"', registry)), expected)
        self.assertNotIn("$", registry)
        self.assertIn("label in StreamDiagnosticGapLabels.stages", self.bounded)
        original_registry = self.bounded.split("val stages: Set<String> = setOf(", 1)[1].split("val kinds:", 1)[0]
        for label in expected:
            self.assertNotIn('"' + label + '"', original_registry)

    def test_disabled_measure_returns_before_label_context_clock_trace_or_allocation(self):
        measure = self.stream.split("fun <T> measure(stage: String", 1)[1].split("fun attach(", 1)[0]
        gate = measure.index("val session = enabledSession() ?: return block()")
        for work in ("canonicalStage(stage)", "context.current()", "session.beginSpan()", "Looper.myLooper()",
                     "System.nanoTime()", "Trace.beginSection", "DiagnosticSpanContext("):
            self.assertLess(gate, measure.index(work))
        self.assertIn("if (onMain && enabledSession() === session)", measure)
        self.assertIn("if (previous?.insideMeasure != true) mainLog?.addCovered", measure)
        self.assertIn("if (revealScope && previous?.insideReveal != true) mainLog?.addReveal", measure)

    def test_placement_is_opt_in_transparent_and_separate_from_measure_draw(self):
        placement = self.modifier.split("internal fun Modifier.streamDiagnosticPlacement", 1)[1]
        self.assertLess(placement.index("if (!StreamPerformanceDiagnostics.enabled) return this"),
                        placement.index("StreamDiagnosticPlacementElement(stage, attribution)"))
        self.assertEqual(placement.count("measurable.measure(constraints)"), 1)
        self.assertEqual(placement.count("child.placeRelative(0, 0)"), 1)
        self.assertIn("return layout(child.width, child.height)", placement)
        self.assertIn("measureDetail(stage) { child.placeRelative(0, 0) }", placement)
        self.assertIn("private data class StreamDiagnosticPlacementElement(val stage: String, val attribution: StreamDiagnosticAttribution?)", placement)
        for forbidden in ("mutableState", "LaunchedEffect", "semantics", "graphicsLayer", "invalidateMeasurement", "Log."):
            self.assertNotIn(forbidden, placement)

    def test_content_commit_reuses_existing_side_effect_entry_and_is_not_a_timing(self):
        emit = self.body.split("internal fun emitChatBodyDiagnosticCommit", 1)[1].split("internal fun traceChatBodyRun", 1)[0]
        self.assertIn("if (!enabled || kind !in CHAT_BODY_TRACE_KINDS) return", emit)
        self.assertIn('sink.record("render.compose")', emit)
        self.assertNotIn("mountId", emit)
        entry = self.body.split("internal fun traceChatBodyRun", 1)[1].split("private object AndroidChatBodyCommitSink", 1)[0]
        self.assertIn("StreamPerformanceDiagnostics.enabled", entry)
        self.assertNotIn("SideEffect {", entry)
        self.assertNotIn("measure(", entry)
        self.assertNotIn("mutableState", entry)

    def test_main_message_v2_output_has_only_numeric_accounting_and_fixed_enums(self):
        template = self.stream.split("v=2 type=mainMessage", 1)[1].split("detail.frames.forEachIndexed", 1)[0]
        for field in ("beginNs", "endNs", "frameDispatch", "coveredNs", "revealNs", "uninstrumentedNs", "nonRevealNs"):
            self.assertIn(field + "=", template)
        self.assertIn("accounting=dispatchSubsetsNotAdditive", template)
        for forbidden in ("msg=", "names[", "messageLine", "tops[", "Handler", "token="):
            self.assertNotIn(forbidden, template)
        log = self.stream.split("internal class MainThreadMessageLog", 1)[1].split("internal class ToggleProbe", 1)[0]
        self.assertIn("private val reveals = LongArray(capacity)", log)
        self.assertIn("private val isFrames = BooleanArray(capacity)", log)
        self.assertIn("if (duration < SLOW_MAIN_MESSAGE_NS) return", log)

    def test_unknown_delay_has_independent_bounded_admission_and_residual_marker(self):
        self.assertIn("internal const val UNKNOWN_DELAY_DETAIL_NS = 8_000_000L", self.stream)
        self.assertIn("val severe = total >= SPIKE_FRAME_NS || unknown >= UNKNOWN_DELAY_DETAIL_NS", self.stream)
        self.assertIn("if ((firstDraw || missed || severe) && session.details.reserveFrame(severe))", self.stream)
        template = self.stream.split("v=2 type=frame", 1)[1].split("val messages = log.between", 1)[0]
        for field in ("unaccountedNs", "overlapNs", "vsyncLateNs"):
            self.assertIn(field + "=${frame." + field + "}", template)
        self.assertIn("accounting=frameMetricsResidualNotAdditive", template)
        self.assertIn("mainRingOverwritten=${log.overwritten}", self.stream)
        self.assertIn("spanOutputTruncated=${detail.spanOutputTruncated}", self.stream)


if __name__ == "__main__":
    unittest.main()
