"""Source contracts for the one-shot haptic when a reasoning block finishes.

Run with Python unittest; no Android SDK is required. These guard the event
wiring, not the motor output or Compose rendering.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
APP_STATE = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt"
HAPTICS = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/haptics/StreamingHaptics.kt"


def function_body(source, name):
    match = re.search(r"fun " + re.escape(name) + r"\(", source)
    if match is None:
        raise AssertionError(f"missing fun {name}")
    start = source.index("{", match.end())
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unclosed fun {name}")


class ReasoningCompletedHapticContractTest(unittest.TestCase):
    def setUp(self):
        self.app = APP_STATE.read_text(encoding="utf-8")
        self.haptics = HAPTICS.read_text(encoding="utf-8")

    def test_completion_reuses_deduplicated_tool_pulse(self):
        body = function_body(self.haptics, "noteReasoningCompleted")
        self.assertIn('noteToolAppeared("$thinkingId-completed", conversationId)', body)

    def test_event_dispatch_compares_streaming_blocks_before_and_after(self):
        body = function_body(self.app, "applyRunEventNow")
        before = body.index("streamingThinkingIds(runId)")
        apply = body.index("applyRunEventBody(runId, event, persistSupplement, replaying)")
        after = body.index("noteCompletedReasoning(runId, reasoningBefore)")
        self.assertLess(before, apply)
        self.assertLess(apply, after)

    def test_replay_and_failure_do_not_pulse(self):
        body = function_body(self.app, "applyRunEventNow")
        guard = body[:body.index("streamingThinkingIds(runId)")]
        self.assertIn("replaying", guard)
        self.assertIn("event is AgentEvent.RunFailed", guard)

    def test_only_finished_blocks_from_before_pulse(self):
        body = function_body(self.app, "noteCompletedReasoning")
        self.assertIn("!message.isStreaming", body)
        self.assertIn("message.id in before", body)
        self.assertIn("StreamingHaptics.noteReasoningCompleted(message.id, conversationId)", body)


if __name__ == "__main__":
    unittest.main()
