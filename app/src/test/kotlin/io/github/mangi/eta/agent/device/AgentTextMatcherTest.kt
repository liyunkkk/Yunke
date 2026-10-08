package io.github.mangi.eta.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文本匹配拆成共用实现后，主屏 wait_for_text 的语义必须保持不变。 */
class AgentTextMatcherTest {
    @Test
    fun defaultModeMatchesCaseInsensitivelyAsSubstring() {
        assertTrue(AgentTextMatcher.matches("Hello AI Agent", "hello", "contains"))
        assertTrue(AgentTextMatcher.matches("abc", "ABC", ""))
        assertFalse(AgentTextMatcher.matches("abc", "abd", "contains"))
    }

    @Test
    fun exactAndPrefixStayCaseSensitive() {
        assertTrue(AgentTextMatcher.matches("abc", "abc", "exact"))
        assertFalse(AgentTextMatcher.matches("abc", "ABC", "exact"))
        assertFalse(AgentTextMatcher.matches("abcd", "abc", "exact"))
        assertTrue(AgentTextMatcher.matches("abcd", "abc", "prefix"))
        assertFalse(AgentTextMatcher.matches("zabc", "abc", "prefix"))
    }

    @Test
    fun regexModeNeverThrowsOnInvalidPattern() {
        assertTrue(AgentTextMatcher.matches("order 42", "[0-9]{2}", "regex"))
        assertFalse(AgentTextMatcher.matches("order", "[0-9]{2}", "regex"))
        assertFalse(AgentTextMatcher.matches("anything", "[", "regex"))
    }
}
