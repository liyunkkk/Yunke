"""Only the three expansion headers suppress visual click indication.

Source contracts, not a runtime rendering or performance test. Keep clickable
semantics and existing callbacks; do not replace them with pointer handlers.
"""
from pathlib import Path
import re
import unittest

from test_agent_chat_viewport_contract import balanced_end, code_only


SOURCE = (Path(__file__).resolve().parents[3] /
          "src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt")


def function_body(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if match is None:
        raise AssertionError(f"Missing function {name}")
    start = source.index("(", match.start())
    end = balanced_end(source, start, "(", ")")
    start = source.index("{", end)
    return source[start + 1:balanced_end(source, start, "{", "}")]


def clickable_calls(source):
    for match in re.finditer(r"\.clickable\s*\(", source):
        opening = source.index("(", match.start())
        end = balanced_end(source, opening, "(", ")")
        yield match.start(), end + 1, source[opening + 1:end]


class ChatExpandTitleIndicationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = code_only(SOURCE.read_text(encoding="utf-8"))

    def header(self, name):
        body = function_body(self.source, name)
        calls = list(clickable_calls(body))
        self.assertEqual(len(calls), 1, f"Expected exactly one title clickable in {name}")
        start, end, args = calls[0]
        self.assertRegex(args, r"\binteractionSource\s*=\s*null\b")
        self.assertRegex(args, r"\bindication\s*=\s*null\b")
        # Explicit false or additional gestures must not replace the normal click path.
        self.assertNotRegex(args, r"\benabled\s*=\s*false\b")
        return body, start, end, args

    def callback(self, body, end):
        start = body.index("{", end)
        self.assertEqual(body[end:start].strip(), "", "Expected the title's trailing callback")
        return body[start + 1:balanced_end(body, start, "{", "}")]

    def test_work_header_keeps_on_toggle_without_indication(self):
        _, _, _, args = self.header("AgentWorkProcessHeader")
        self.assertRegex(args, r"\bonClick\s*=\s*onToggle\b")

    def test_thinking_header_keeps_expansion_callback_without_indication(self):
        body, _, end, _ = self.header("ThinkingRow")
        callback = self.callback(body, end)
        self.assertIn("anchorBottom = expansionHoldsBottom()", callback)
        self.assertIn("manuallyExpanded = true", callback)
        self.assertIn("expandedByTap = !expanded", callback)
        self.assertIn("expanded = !expanded", callback)
        self.assertIn("StreamPerformanceDiagnostics.markToggle", callback)

    def test_tool_header_keeps_expansion_callback_without_indication(self):
        body, _, end, _ = self.header("ToolActivityInline")
        callback = self.callback(body, end)
        self.assertIn("anchorBottom = expansionHoldsBottom()", callback)
        self.assertIn("expandedByTap = !isExpanded", callback)
        self.assertIn("isExpanded = !isExpanded", callback)
        self.assertIn("StreamPerformanceDiagnostics.markToggle", callback)

    def test_tool_clickable_stays_inside_has_details_guard(self):
        body, call_start, call_end, _ = self.header("ToolActivityInline")
        guarded = False
        for match in re.finditer(r"\bif\s*\(\s*hasDetails\s*\)\s*\{", body):
            start = body.index("{", match.start())
            end = balanced_end(body, start, "{", "}")
            guarded |= start < call_start < call_end < end
        self.assertTrue(guarded, "The clickable must stay inside the hasDetails branch")

    def test_no_ripple_change_is_limited_to_three_header_calls(self):
        suppressed = [args for _, _, args in clickable_calls(self.source)
                      if re.search(r"\bindication\s*=\s*null\b", args)]
        self.assertEqual(len(suppressed), 3)
        self.assertNotIn("LocalIndication", self.source)


if __name__ == "__main__":
    unittest.main()
