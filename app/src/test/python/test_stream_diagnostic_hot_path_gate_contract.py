"""Narrow source contracts for diagnostic hot-path work; no Android or latency claims."""
import re
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
COMPONENTS = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/components"


def normalized_lines(text):
    """Ignore indentation only, not fields, expressions, calls or their ordering."""
    return "\n".join(line.strip() for line in textwrap.dedent(text).strip().splitlines())


PARTS = '''
val parts = "${page.fields()} totalUs=${total / 1000} deadlineUs=${deadline / 1000} " +
    "miss=${if (missed) 1 else 0} " +
    "unknownUs=${unknown / 1000} inputUs=${input / 1000} " +
    "animUs=${animation / 1000} layoutUs=${layout / 1000} drawUs=${draw / 1000} " +
    "syncUs=${sync / 1000} cmdUs=${command / 1000} gpuUs=${gpu / 1000} " +
    "vsyncLateUs=${late.coerceAtLeast(0) / 1000}"
'''

# The listener minus the relocated, gate-only formatting. Keep aggregation/admission/expiry exact.
LISTENER_WITHOUT_PARTS = '''
val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
    val delivered = System.nanoTime()
    val intendedFrame = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
    if (session.admitFrame(intendedFrame, AppFileLogger.isEnabled())) {
        val firstDraw = frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
        val total = frame.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = frame.getMetric(FrameMetrics.DEADLINE)
        val unknown = frame.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
        val input = frame.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
        val animation = frame.getMetric(FrameMetrics.ANIMATION_DURATION)
        val layout = frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        val draw = frame.getMetric(FrameMetrics.DRAW_DURATION)
        val sync = frame.getMetric(FrameMetrics.SYNC_DURATION)
        val command = frame.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
        val swap = frame.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        val gpu = frame.getMetric(FrameMetrics.GPU_DURATION)
        val missed = deadline > 0 && total > deadline
        val intended = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val page = pages.attributeFrame(intended, total)
        // All metric stages have a timestamp-resolved page segment, including transitions.
        fun metric(stage: String, ns: Long, value: Long) {
            session.recordMetric(page.aggregatePage.ordinal, stage, ns, value)
        }
        session.observerCosts.add(DiagnosticObserverCosts.Phase.CallbackLag, delivered - (intended + total))
        val severe = total >= SPIKE_FRAME_NS || unknown >= UNKNOWN_DELAY_DETAIL_NS
        if ((firstDraw || missed || severe) && session.details.reserveFrame(severe)) {
            val record = DiagnosticFrameRecord(intended, frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                total, deadline, page.start.ordinal, page.end.ordinal, page.changed, dropped,
                unknown, input, animation, layout, draw, sync, command, swap, gpu,
                firstDraw = firstDraw, pageSegment = page.startSegment)
            // One bounded capture per retained frame, never for normal or budget-rejected frames.
            // Include retention, source matching and dispatch capture in non-recursive observer cost.
            session.observerCosts.observe(DiagnosticObserverCosts.Phase.Protect) {
                val evidence = session.details.protectFrame(record)
                val listSnapshot = if (page.start == FrameDiagnosticPage.Chat || page.start == FrameDiagnosticPage.Home)
                    session.listSamples.forFrame(record, evidence) else null
                val mainMessages = log.timingsBetween(intended - FRAME_CORRELATION_LOOKBACK_NS, intended + total)
                    .sortedByDescending { diagnosticOverlapNs(it.beginNs, it.endNs, intended, intended + total) }
                session.details.frame(record.copy(listSnapshot = listSnapshot, mainMessages = mainMessages,
                    sourceWindowLoss = evidence.sourceWindowLoss, sourceWindowUnknown = evidence.sourceWindowUnknown))
            }
        }
        // Capture first-draw evidence without changing legacy steady-frame/probe statistics.
        if (firstDraw) {
            metric("frame.firstDraw", total, if (missed) 1 else 0)
            return@OnFrameMetricsAvailableListener
        }
        session.record(page.aggregatePage.frameStage, total, if (missed) 1 else 0)
        metric("frame.total", total, if (missed) 1 else 0)
        metric("frame.steady", total, if (missed) 1 else 0)
        metric("frame.layout", layout, 0)
        metric("frame.draw", draw, 0)
        metric("frame.sync", sync, 0)
        metric("frame.gpu", gpu, 0)
        metric("frame.input", input, 0)
        metric("frame.unknown", unknown, 0)
        metric("frame.animation", animation, 0)
        metric("frame.command", command, 0)
        metric("frame.swap", swap, 0)
        val gap = frameUnaccountedNs(total, unknown, input, animation, layout, draw, sync, command, swap)
        if (gap >= 0) metric("frame.unaccounted", gap, 0)
        else metric("frame.overlap", -gap, 1)
        val late = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP) - intended
        metric("frame.vsyncLate", late.coerceAtLeast(0), if (late > 8_333_333L) 1 else 0)
        metric("frame.metricsDropped", 0, dropped.toLong())
        metric("frame.deadline", deadline, 0)
        val target = probe
        val inWindow = target != null && !target.finished && intended >= target.startNs - FRAME_PROBE_LEAD_NS
        if (inWindow) {
            val tenths = (intended - target!!.startNs) / 100_000
            val line = "sinceTapMs=${tenths / 10}.${kotlin.math.abs(tenths % 10)} $parts"
            if (target.addFrame(line, total / 1000, missed) || target.expired(System.nanoTime())) {
                finishProbe(target, reportProbe)
            }
        } else {
            if (target != null && target.expired(System.nanoTime())) finishProbe(target, reportProbe)
            // Detailed anomalies (including <33ms deadline misses) are reserved above and formatted at emit.
        }
    }
}
'''


class StreamDiagnosticHotPathGateContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.diagnostics = (COMPONENTS / "StreamPerformanceDiagnostics.kt").read_text(encoding="utf-8")
        cls.listener = cls.diagnostics.split("val listener = Window.OnFrameMetricsAvailableListener", 1)[1]
        cls.listener = "val listener = Window.OnFrameMetricsAvailableListener" + cls.listener.split(
            "window.addOnFrameMetricsAvailableListener(listener, handler)", 1)[0]

    def test_parts_is_built_only_inside_the_existing_probe_gate_with_identical_fields(self):
        before_gate, gated = self.listener.split("if (inWindow) {", 1)
        inside, after_gate = gated.split("} else {", 1)
        self.assertNotRegex(before_gate, r"\bparts\b")
        self.assertNotRegex(after_gate, r"\bparts\b")
        self.assertEqual(len(re.findall(r"\bparts\b", inside)), 2)
        actual_parts = inside.split("val tenths =", 1)[0]
        self.assertEqual(normalized_lines(PARTS), normalized_lines(actual_parts))

    def test_metrics_admission_and_expiry_are_unchanged_with_bounded_anomaly_capture(self):
        listener_lines = normalized_lines(self.listener)
        parts_lines = normalized_lines(PARTS)
        self.assertEqual(listener_lines.count(parts_lines), 1)
        without_parts = listener_lines.replace(parts_lines + "\n", "", 1)
        self.assertEqual(normalized_lines(LISTENER_WITHOUT_PARTS), without_parts)
        first_draw, steady = self.listener.split("return@OnFrameMetricsAvailableListener", 1)
        self.assertIn('metric("frame.firstDraw", total, if (missed) 1 else 0)', first_draw)
        self.assertIn('session.details.frame(record.copy(', first_draw)
        self.assertNotIn('metric("frame.total"', first_draw)
        self.assertNotIn('target.addFrame(', first_draw)
        self.assertIn('metric("frame.steady", total, if (missed) 1 else 0)', steady)

    def test_probe_precision_capacity_and_expiry_are_unchanged(self):
        for exact in (
            "internal const val TOGGLE_PROBE_FRAMES = 150",
            "internal const val TOGGLE_PROBE_MAX_NS = 1_250_000_000L",
            "private const val FRAME_PROBE_LEAD_NS = 17_000_000L",
            "return frames.size >= TOGGLE_PROBE_FRAMES",
            "fun expired(now: Long): Boolean = now - startNs >= TOGGLE_PROBE_MAX_NS",
        ):
            self.assertIn(exact, self.diagnostics)

    def test_measure_observer_uses_stage_equality_and_default_node_invalidation(self):
        helper = (COMPONENTS / "StreamDiagnosticModifier.kt").read_text(encoding="utf-8")
        measure, draw = helper.split("internal fun Modifier.streamDiagnosticDraw", 1)
        draw = draw.split("/** Placement-only observer.", 1)[0]
        self.assertIn("if (!StreamPerformanceDiagnostics.enabled) return this", measure)
        self.assertIn("return this.then(StreamDiagnosticMeasureElement(stage, attribution))", measure)
        self.assertIn("private data class StreamDiagnosticMeasureElement(val stage: String, val attribution: StreamDiagnosticAttribution?)", measure)
        self.assertIn("ModifierNodeElement<StreamDiagnosticMeasureNode>()", measure)
        self.assertIn("override fun create() = StreamDiagnosticMeasureNode(stage, attribution)", measure)
        self.assertIn("override fun update(node: StreamDiagnosticMeasureNode) { node.stage = stage; node.attribution = attribution }", measure)
        self.assertIn("Modifier.Node(), LayoutModifierNode", measure)
        self.assertEqual(measure.count("measurable.measure(constraints)"), 1)
        self.assertEqual(measure.count("layout(child.width, child.height) { child.placeRelative(0, 0) }"), 1)
        self.assertLess(measure.index("measureDetail(stage)"), measure.index("measurable.measure(constraints)"))
        measure_code = re.sub(r"/\*.*?\*/", "", measure, flags=re.S)
        measure_code = re.sub(r"//[^\n]*", "", measure_code)
        for forbidden in ("shouldAutoInvalidate", "invalidateMeasurement", "override fun minIntrinsic",
                          "override fun maxIntrinsic", "mutableState", "semantics", "graphicsLayer"):
            self.assertNotIn(forbidden, measure_code)
        self.assertEqual(normalized_lines(draw), normalized_lines('''
            (stage: String, attribution: StreamDiagnosticAttribution? = null): Modifier {
                // A construction in composition observes only attach/detach, even when initially OFF.
                StreamPerformanceDiagnostics.sessionGeneration.longValue
                if (!StreamPerformanceDiagnostics.enabled) return this
                return this.then(StreamDiagnosticDrawElement(stage, attribution))
            }

            private data class StreamDiagnosticDrawElement(val stage: String, val attribution: StreamDiagnosticAttribution?) : ModifierNodeElement<StreamDiagnosticDrawNode>() {
                override fun create() = StreamDiagnosticDrawNode(stage, attribution)
                override fun update(node: StreamDiagnosticDrawNode) { node.stage = stage; node.attribution = attribution }
                override fun InspectorInfo.inspectableProperties() {
                    name = "streamDiagnosticDraw"
                    properties["stage"] = stage
                }
            }

            private class StreamDiagnosticDrawNode(var stage: String, var attribution: StreamDiagnosticAttribution?) : Modifier.Node(), DrawModifierNode {
                override fun ContentDrawScope.draw() {
                    StreamPerformanceDiagnostics.withRenderAttribution(attribution) {
                        StreamPerformanceDiagnostics.measureDetail(stage) { drawContent() }
                    }
                }
            }
        '''))


if __name__ == "__main__":
    unittest.main()
