"""Runtime diagnostic source guards only; not Android execution or latency evidence."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def source(name):
    return (SRC / name).read_text(encoding="utf-8")


def code(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def compact(text):
    return re.sub(r"\s+", "", code(text))


def numeric_diagnostic_calls(text):
    """Keep nested numeric calls such as toLong()/nanoTime() in the argument contract."""
    pattern = r"\bStreamPerformanceDiagnostics\s*\.\s*(measure|record|recordForSession)\s*\("
    for call in re.finditer(pattern, text):
        depth = 1
        for end in range(call.end(), len(text)):
            depth += (text[end] == "(") - (text[end] == ")")
            if depth == 0:
                yield call.group(1), compact(text[call.end():end])
                break
        else:
            raise AssertionError("Unclosed numeric diagnostic call")


class RuntimeStreamStageDiagnosticsContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = source("agent/runtime/AgentRuntimeClient.kt")
        cls.executor = source("agent/runtime/AgentRuntimeRunExecutor.kt")
        cls.recorder = source("agent/runtime/AgentRunCheckpointRecorder.kt")
        cls.adapter = source("agent/runtime/RuntimeStreamStageTiming.kt")
        cls.diag = source("ui/components/StreamPerformanceDiagnostics.kt")

    def test_producer_gate_is_reachable_only_in_the_existing_ui_session(self):
        app = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        self.assertNotIn(ANDROID + "process", app.attrib)
        for tag, name in (("service", ".agent.runtime.AgentRuntimeService"),
                          ("activity", ".ui.MainActivity")):
            node = next(n for n in app.findall(tag) if n.get(ANDROID + "name") == name)
            self.assertNotIn(ANDROID + "process", node.attrib)
            self.assertNotEqual(node.get(ANDROID + "isolatedProcess"), "true")
        self.assertIn("@Volatile private var active: Session? = null", self.diag)
        enabled_session = re.search(
            r"private fun enabledSession\(\): Session\? \{(.*?)\n    \}", code(self.diag), re.S)
        self.assertIsNotNone(enabled_session)
        self.assertEqual(compact(enabled_session.group(1)), compact('''
            val session = active ?: return null
            return session.takeIf { StreamDiagnosticControl.allowed && AppFileLogger.isEnabled() && !it.closed && it.stopCutoffNs == null }
        '''))
        self.assertEqual(len(re.findall(r"\bactive\b", enabled_session.group(1))), 1)
        self.assertIn("val enabled: Boolean get() = enabledSession() != null", self.diag)
        self.assertIn("@Volatile var closed = false", self.diag)
        self.assertIn("lifecycleState.isAtLeast(Lifecycle.State.RESUMED)", self.diag)
        self.assertIn("loggingEnabled && diagnosticAllowed && resumed && window != null", self.diag)
        self.assertIn("if (!StreamPerformanceDiagnostics.enabled) return block()", self.adapter)
        self.assertIn("internal inline fun <T> measureRuntimeStreamStage", self.adapter)
        self.assertIn("return StreamPerformanceDiagnostics.measure(stage) { block() }", self.adapter)
        self.assertNotIn("catch", code(self.adapter))
        runtime = code(self.client + self.executor + self.recorder + self.adapter)
        self.assertNotIn("StreamPerformanceDiagnostics.attach", runtime)
        self.assertNotIn("System.nanoTime()", code(self.adapter))

    def test_stage_vocabulary_is_finite_and_no_payload_is_measured(self):
        expected = {
            "ipc.client.receive", "ipc.attach.receive",
            "ipc.client.decode.live", "ipc.client.callback.live",
            "ipc.attach.decode.live", "ipc.attach.decode.replay",
            "ipc.attach.callback.live", "ipc.attach.callback.replay", "ipc.attach.callback.replayBatch",
            "runtime.checkpoint.accept", "runtime.checkpoint.append",
            "runtime.checkpoint.lockWait", "runtime.checkpoint.merge",
            "runtime.checkpoint.buffer.chars", "runtime.checkpoint.buffer.events",
            "runtime.checkpoint.buffer.residency",
            "runtime.checkpoint.flush.boundary", "runtime.checkpoint.flush.size",
            "runtime.checkpoint.flush.timer", "runtime.checkpoint.flush.seal",
        }
        runtime = code(self.client + self.executor + self.recorder)
        labels = set(re.findall(r'"((?:ipc\.(?:client|attach)|runtime\.checkpoint)\.[^"\n]+)"', runtime))
        self.assertEqual(expected, labels)
        self.assertEqual(len(labels), 20)

        # Count/clock arguments are the only diagnostic values; never permit a payload value.
        diagnostics = runtime + code(self.adapter)
        calls = list(numeric_diagnostic_calls(diagnostics))
        self.assertCountEqual(calls, [
            ("record", '"ipc.delta.delay",ns=it'),
            ("record", '"runtime.checkpoint.lockWait",System.nanoTime()-requested'),
            ("measure", '"runtime.checkpoint.merge",checkpointEvent.deltaChars.toLong()'),
            ("recordForSession", 'diagnosticGeneration,"runtime.checkpoint.buffer.chars",value=event.deltaChars.toLong()'),
            ("recordForSession", 'diagnosticGeneration,"runtime.checkpoint.buffer.events",value=pendingObservedDeltas'),
            ("recordForSession", 'diagnosticGeneration,"runtime.checkpoint.buffer.residency",System.nanoTime()-observedAt'),
            ("measure", "stage"),  # The gated adapter forwards only the finite stage.
        ])
        self.assertEqual([compact(value) for value in re.findall(r"\bvalue\s*=\s*([^\n]+)", diagnostics)],
                         ["event.deltaChars.toLong())", "pendingObservedDeltas)"])
        for method, arguments in calls:
            with self.subTest(method=method, arguments=arguments):
                self.assertNotRegex(re.sub(r'"(?:\\.|[^"\\])*"', "", arguments),
                                    r"\.(?:delta|text|content|body|payload|prompt|arguments|toString|javaClass)\b")
                self.assertNotRegex(arguments, r"\b(?:data|runId|diagnosticRunId|request)\b|\$")
        self.assertNotIn(".note(", runtime)
        self.assertNotIn(".note(", code(self.adapter))
        self.assertNotIn(".javaClass", code(self.adapter))

    def test_client_keeps_delay_decode_and_callback_in_order(self):
        body = self.client.split("private class ClientHandler", 1)[1].split("private class DrainHandler", 1)[0]
        self.assertLess(body.index("recordDeliveryTiming(data, live = true)"), body.index('"ipc.client.decode.live"'))
        self.assertLess(body.index("AgentRuntimeWire.eventFromBundle(data)"), body.index('"ipc.client.callback.live"'))
        self.assertEqual(body.count("onEvent(it)"), 1)
        self.assertIn("event?.let {", body)
        delay = self.client.split("fun recordDeliveryTiming", 1)[1].split("const val RESPONSE_TIMEOUT_SECONDS", 1)[0]
        self.assertEqual(compact(delay), compact('''(data: android.os.Bundle, live: Boolean) {
            StreamDeliveryTiming.delayNs(data.getLong(StreamDeliveryTiming.KEY, 0L),
                android.os.SystemClock.elapsedRealtimeNanos(), live)?.let {
                io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics.record("ipc.delta.delay", ns = it)
            }
        }'''))

    def test_attach_times_original_callbacks_not_replay_buffering(self):
        body = self.client.split("private class AttachHandler", 1)[1].split("internal companion object", 1)[0]
        self.assertIn('if (live) "ipc.attach.decode.live" else "ipc.attach.decode.replay"', body)
        self.assertIn("val live = delivery.isLive", body)
        self.assertIn("recordDeliveryTiming(data, live = live)", body)
        self.assertIn("event?.let(delivery::event)", body)
        self.assertIn('measureRuntimeStreamStage("ipc.attach.callback.replayBatch") { onReplay(events) }', body)
        self.assertIn('measureRuntimeStreamStage("ipc.attach.callback.replay") { onEvent(event) }', body)
        self.assertIn('measureRuntimeStreamStage("ipc.attach.callback.live") { onEvent(event) }', body)
        self.assertEqual(body.count("onEvent(event)"), 2)  # Mutually exclusive replay/live callbacks.
        self.assertIn("delivery.result(result)", body)
        self.assertIn("delivery.attachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))", body)

    def test_checkpoint_accept_stays_in_both_before_dispatch_callbacks(self):
        body = self.executor.split("@Synchronized private fun acceptEvent", 1)[1]
        question, normal = body.split("} else {", 1)
        for branch, call in ((question, "AgentQuestionEventPublisher.publish(session, event) {"),
                             (normal, "if (!session.emit(event) {")):
            self.assertLess(branch.index(call), branch.index('"runtime.checkpoint.accept"'))
            self.assertEqual(branch.count("recorder.accept(event)"), 1)
        self.assertIn("}) return", normal)
        self.assertLess(normal.index("recorder.accept(event)"), normal.index("archivedEvents += event"))
        self.assertIn("executor monitor acquisition is", self.executor)
        self.assertIn("excluded.", self.executor)
        self.assertNotIn("monitorWait", body)
        self.assertNotIn("synchronized(", code(body))

    def test_existing_flush_thresholds_and_mutation_order_remain(self):
        self.assertIn("pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||\n                elapsed >= MAX_BUFFERED_DELTA_NANOS", self.recorder)
        self.assertIn("private const val MAX_BUFFERED_DELTA_CHARS = 512", self.recorder)
        self.assertIn("private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L", self.recorder)
        flush = self.recorder.split("private fun flushPendingDelta", 1)[1].split("private fun append", 1)[0]
        positions = [flush.index(s) for s in ("val event = pendingDelta ?: return", "measureRuntimeStreamStage(stage)",
                                            "pendingDelta = null", "append(event)", "lastFlushNanos = nanoTime()")]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(self.recorder.count("sortIndex = nextSortIndex++"), 1)
        self.assertIn('sealed = true\n        flushPendingDelta("runtime.checkpoint.flush.seal")', self.recorder)
        self.assertIn("@Synchronized fun discard() {\n        sealed = true\n        pendingDelta = null\n        clearPendingObservations()\n        AgentRunCheckpointStore.remove(appContext, runId)\n    }", self.recorder)


if __name__ == "__main__":
    unittest.main()
