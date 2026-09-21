package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplexAsrHypothesisTest {
    @Test fun secondPassDoesNotBlinkTrailingPunctuation() {
        var shown = ""
        for (hypothesis in listOf("你", "你好", "你好。", "你好", "你好。")) {
            shown = DuplexAsrHypothesis.display(shown, hypothesis, completed = false)
        }
        assertEquals("你好。", shown)
        assertEquals("你好。", DuplexAsrHypothesis.display("你好。", "你好", completed = false))
    }

    @Test fun laterSpeechAndPunctuationChangesStillApply() {
        assertEquals("你好世界", DuplexAsrHypothesis.display("你好。", "你好世界", completed = false))
        assertEquals("你好！", DuplexAsrHypothesis.display("你好。", "你好！", completed = false))
        assertEquals("你好吗", DuplexAsrHypothesis.display("你好。", "你好吗", completed = false))
    }

    @Test fun completedResultIsAuthoritativeIncludingDroppedPunctuation() {
        assertEquals("你好", DuplexAsrHypothesis.display("你好。", "你好", completed = true))
        assertEquals("你好？", DuplexAsrHypothesis.display("你好。", "你好？", completed = true))
        assertEquals("你好。", DuplexAsrHypothesis.display("你好。", "", completed = true))
    }

    @Test fun englishAndEllipsisTrailingMarksAreStabilized() {
        assertEquals("Hello.", DuplexAsrHypothesis.display("Hello.", "Hello", completed = false))
        assertEquals("好的……", DuplexAsrHypothesis.display("好的……", "好的", completed = false))
        assertEquals("好的", DuplexAsrHypothesis.stripTrailingPunctuation("好的……"))
    }
}
