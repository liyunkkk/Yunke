package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class StreamingMarkdownImageTransformerTest {
    @Test fun plainContentReusesTheSameStatelessInstance() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val first = policy.forContent("Plain paragraph")
        assertSame(first, policy.forContent("Plain paragraph\n\nTail grows"))
        assertSame(first, policy.forContent("Corrected **bold** and `code`"))
    }

    @Test fun bracketSyntaxKeepsFreshValuesIncludingAfterRemoval() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val plain = policy.forContent("Plain")
        val reference = policy.forContent("[label][ref]\n\n[ref]: https://example.test/one")
        assertNotSame(plain, reference)
        val changed = policy.forContent("[label][ref]\n\n[ref]: https://example.test/two")
        assertNotSame(reference, changed)
        assertNotSame(changed, policy.forContent("Correction without brackets"))
        val removed = policy.forContent("Correction without brackets")
        assertNotSame(removed, policy.forContent("Correction without brackets"))
    }

    @Test fun eachDocumentHasItsOwnConservativeSyntaxHistory() {
        val old = StreamingMarkdownImageTransformerPolicy()
        old.forContent("![image][ref]")
        val new = StreamingMarkdownImageTransformerPolicy()
        val first = new.forContent("No bracket in a new document")
        assertSame(first, new.forContent("New tail"))
        assertNotSame(old.forContent("No bracket"), old.forContent("No bracket"))
    }
    @Test fun allBracketSyntaxConservativelyRetainsFreshBehavior() {
        listOf("[ref]", "[text][ref]", "[text][]", "[ref]: https://example.test",
            "![alt][ref]", "[inline](https://example.test)", "`code[index]`", "\\[escaped]")
            .forEach { text ->
                val policy = StreamingMarkdownImageTransformerPolicy()
                assertNotSame(text, policy.forContent(text), policy.forContent(text))
            }
    }

}
