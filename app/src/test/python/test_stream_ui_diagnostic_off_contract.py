"""Static observer gates only; runtime draw and performance acceptance remain separate."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
COMPONENTS = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/components"


class StreamUiDiagnosticOffContractTest(unittest.TestCase):
    def test_tail_sampling_short_circuits_before_layout_and_allocation(self):
        source = (COMPONENTS / "AgentChatBody.kt").read_text()
        start = source.index("// 快速输出时尾部越过静止线")
        block = source[start:source.index("val currentBottomItemIndex", start)]
        self.assertLess(block.index("sessionGeneration.longValue"), block.index("StreamPerformanceDiagnostics.enabled"))
        self.assertIn("generation to (StreamPerformanceDiagnostics.enabled && (currentStreaming.value || isBottomSettling))", block)
        self.assertIn("collectLatest { (generation, active) ->", block)
        gate = block.index("return@snapshotFlow null")
        self.assertLess(gate, block.index("val info = scrollState.layoutInfo"))
        self.assertLess(gate, block.index("TailBreachSample("))
        self.assertLess(block.index("sample == null"), block.index("val over = sample.overPx"))
        self.assertEqual(3, block.count("sessionGeneration.longValue"))
        self.assertIn('"tail.clippedPx" else "tail.breachPx"', block)
        self.assertNotIn("bottomFollowDecisions", block)
        self.assertNotIn("scrollToItem", block)

    def test_draw_uses_equal_elements_with_one_transparent_draw(self):
        source = (COMPONENTS / "StreamDiagnosticModifier.kt").read_text()
        start = source.index("internal fun Modifier.streamDiagnosticDraw")
        block = source[start:source.index("/** Placement-only observer", start)]
        self.assertLess(block.index("sessionGeneration.longValue"), block.index("if (!StreamPerformanceDiagnostics.enabled) return this"))
        self.assertIn("return this.then(StreamDiagnosticDrawElement(stage, attribution))", block)
        self.assertIn("private data class StreamDiagnosticDrawElement", block)
        self.assertIn("Modifier.Node(), DrawModifierNode", block)
        self.assertIn("override fun ContentDrawScope.draw()", block)
        self.assertEqual(1, block.count("drawContent()"))
        self.assertNotIn("drawWithContent", block)
        self.assertNotIn("invalidateDraw", block)
        self.assertNotIn("graphicsLayer", block)


if __name__ == "__main__":
    unittest.main()
