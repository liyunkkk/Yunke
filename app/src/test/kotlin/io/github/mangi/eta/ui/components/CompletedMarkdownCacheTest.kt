package io.github.mangi.eta.ui.components

import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class CompletedMarkdownCacheTest {
    @Test fun hitIsImmediatelySuccessfulAndUsesExactContent() {
        val cache = CompletedMarkdownCache()
        val content = "**completed**"
        val success = success(content)

        assertNull(cache.get(content))
        assertTrue(cache.put(content, success, emptyMap()))
        val cached = requireNotNull(cache.get(content))
        val cachedSuccess = cached.state.value as State.Success
        assertEquals(content, cachedSuccess.content)
        assertSame(success.node, cachedSuccess.node)
        assertTrue(cachedSuccess.linksLookedUp)
        assertSameDocument(cached, cache.get(String(content.toCharArray())))
        assertNull(cache.get(content + "!"))
        assertNull(cache.get("**Completed**"))
        assertEquals(1, cache.entryCount)
        assertEquals(content.length, cache.contentChars)
    }

    @Test fun parsingASnapshotReturnsItsExistingSuccessWithoutChangingState() = runBlocking {
        val cache = CompletedMarkdownCache()
        val original = success("already parsed")
        assertTrue(cache.put(original.content, original, emptyMap()))
        val cached = requireNotNull(cache.get(original.content))
        val completed = cached.state.value
        val links = cached.links.value

        repeat(3) {
            assertSame(completed, cached.parse())
            assertSame(completed, cached.state.value)
            assertSame(links, cached.links.value)
        }
    }

    @Test fun retainedOldSuccessCannotPopulateANewContentKey() {
        val cache = CompletedMarkdownCache()
        val old = success("old content")
        assertTrue(cache.put(old.content, old, emptyMap()))
        val cachedOld = cache.get(old.content)

        assertFalse(cache.put("new content", old, emptyMap()))
        assertNull(cache.get("new content"))
        assertSameDocument(cachedOld, cache.get(old.content))
        assertEquals(1, cache.entryCount)
        assertEquals(old.content.length, cache.contentChars)
    }

    @Test fun sourceMarkdownStateUpdatesCannotContaminateTheOldContentKey() {
        val old = success("old")
        val source = object : MarkdownState {
            override val state = MutableStateFlow<State>(old)
            override val links = MutableStateFlow<Map<String, String?>>(mapOf("ref" to "old-link"))
            override suspend fun parse(): State = error("Cache must not invoke the source parser")
        }
        val cache = CompletedMarkdownCache()
        assertTrue(cache.put("old", old, source.links.value))
        val cached = requireNotNull(cache.get("old"))

        source.state.value = success("new")
        source.links.value = mapOf("ref" to "new-link")
        old.referenceLinkHandler.store("ref", "new-link")
        assertNotSame(source, cached)
        assertEquals("old", (cached.state.value as State.Success).content)
        assertEquals("old-link", cached.links.value["ref"])
        assertEquals("old-link", (cached.state.value as State.Success).referenceLinkHandler.find("ref"))
        assertNull(cache.get("new"))
    }

    @Test fun successWithoutLinkLookupIsRejectedWithoutReplacingAHealthyEntry() {
        val cache = CompletedMarkdownCache()
        val success = success("same")
        assertTrue(cache.put("same", success, emptyMap()))
        val cached = cache.get("same")

        assertFalse(cache.put("same", success.copy(linksLookedUp = false), emptyMap()))
        assertFalse(cache.put("different", success("different", linksLookedUp = false), emptyMap()))
        assertSameDocument(cached, cache.get("same"))
        assertNull(cache.get("different"))
        assertEquals(1, cache.entryCount)
        assertEquals(4, cache.contentChars)
    }

    @Test fun linksAndReferenceHandlerAreIndependentSnapshots() {
        val links = linkedMapOf<String, String?>(
            "guide" to "https://example.test/original",
            "missing" to null,
        )
        val sourceHandler = ReferenceLinkHandlerImpl().apply {
            links.forEach { (label, destination) -> store(label, destination) }
        }
        val success = success("[Guide][guide]").copy(referenceLinkHandler = sourceHandler)
        val cache = CompletedMarkdownCache()
        assertTrue(cache.put(success.content, success, links))
        val cached = requireNotNull(cache.get(success.content))
        val cachedSuccess = cached.state.value as State.Success

        links["guide"] = "https://example.test/changed"
        links["added"] = "https://example.test/added"
        sourceHandler.store("guide", "https://example.test/changed")
        sourceHandler.store("added", "https://example.test/added")

        assertNotSame(success, cachedSuccess)
        assertNotSame(sourceHandler, cachedSuccess.referenceLinkHandler)
        assertSame(success.node, cachedSuccess.node)
        assertEquals("https://example.test/original", cached.links.value["guide"])
        assertTrue(cached.links.value.containsKey("missing"))
        assertNull(cached.links.value["missing"])
        assertFalse(cached.links.value.containsKey("added"))
        assertEquals("https://example.test/original", cachedSuccess.referenceLinkHandler.find("GUIDE"))
        assertEquals("", cachedSuccess.referenceLinkHandler.find("added"))
        assertEquals("", cachedSuccess.referenceLinkHandler.find("missing"))
    }

    @Test fun snapshotFlowsAndMapsAreReadOnlyButEachRendererOwnsItsLinkHandler() {
        val cache = CompletedMarkdownCache()
        assertTrue(cache.put("read only", success("read only"), mapOf("ref" to "original")))
        val cached = requireNotNull(cache.get("read only"))
        assertFalse(cached.state is MutableStateFlow<*>)
        assertFalse(cached.links is MutableStateFlow<*>)

        expectReadOnly {
            @Suppress("UNCHECKED_CAST")
            val mutableLinks = cached.links.value as MutableMap<String, String?>
            mutableLinks["ref"] = "changed"
        }
        val handler = (cached.state.value as State.Success).referenceLinkHandler
        handler.store("ref", "changed")
        handler.store("inline", "https://example.test/inline")
        assertEquals("changed", handler.find("ref"))
        assertEquals("https://example.test/inline", handler.find("INLINE"))
        assertEquals("original", cached.links.value["ref"])
        val other = requireNotNull(cache.get("read only"))
        val otherHandler = (other.state.value as State.Success).referenceLinkHandler
        assertNotSame(handler, otherHandler)
        assertEquals("original", otherHandler.find("ref"))
        assertEquals("", otherHandler.find("inline"))
    }

    @Test fun repeatingTheSameSuccessKeepsTheDocumentAndRefreshesRecency() {
        val cache = CompletedMarkdownCache(maxEntries = 2)
        val first = success("first")
        val links = mapOf("guide" to "https://example.test/guide")
        assertTrue(cache.put("first", first, links))
        val cached = requireNotNull(cache.get("first"))
        assertTrue(cache.put("second", success("second"), emptyMap()))

        repeat(3) { assertTrue(cache.put("first", first, links)) }
        assertSameDocument(cached, cache.get("first"))
        assertEquals(2, cache.entryCount)
        assertEquals(11, cache.contentChars)
        assertTrue(cache.put("third", success("third"), emptyMap()))
        assertNull(cache.get("second"))
        assertSameDocument(cached, cache.get("first"))
        assertEquals(10, cache.contentChars)
    }

    @Test fun updatedLinksReplaceOnlyTheSnapshotAndDoNotDoubleCountContent() {
        val cache = CompletedMarkdownCache()
        val success = success("same")
        val links = mutableMapOf<String, String?>("ref" to "before")
        assertTrue(cache.put("same", success, links))
        val before = requireNotNull(cache.get("same"))
        links["ref"] = "after"
        assertTrue(cache.put("same", success, links))
        val after = requireNotNull(cache.get("same"))

        assertNotSame(before, after)
        assertEquals("before", before.links.value["ref"])
        assertEquals("after", after.links.value["ref"])
        assertEquals("before", (before.state.value as State.Success).referenceLinkHandler.find("ref"))
        assertEquals("after", (after.state.value as State.Success).referenceLinkHandler.find("ref"))
        assertEquals(1, cache.entryCount)
        assertEquals(4, cache.contentChars)
    }

    @Test fun getPromotesEntryForCountBasedLruEviction() {
        val cache = CompletedMarkdownCache(maxEntries = 2)
        assertTrue(cache.put("a", success("a"), emptyMap()))
        assertTrue(cache.put("bb", success("bb"), emptyMap()))
        val first = cache.get("a")
        assertTrue(cache.put("ccc", success("ccc"), emptyMap()))

        assertNull(cache.get("bb"))
        assertSameDocument(first, cache.get("a"))
        assertNotNull(cache.get("ccc"))
        assertEquals(2, cache.entryCount)
        assertEquals(4, cache.contentChars)
    }

    @Test fun characterBudgetEvictsAsManyLeastRecentEntriesAsNeeded() {
        val cache = CompletedMarkdownCache(maxEntries = 8, maxContentChars = 6)
        for (content in listOf("a", "bb", "ccc")) {
            assertTrue(cache.put(content, success(content), emptyMap()))
        }
        assertEquals(3, cache.entryCount)
        assertEquals(6, cache.contentChars)
        assertNotNull(cache.get("a"))
        assertTrue(cache.put("dddd", success("dddd"), emptyMap()))

        assertNull(cache.get("bb"))
        assertNull(cache.get("ccc"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("dddd"))
        assertEquals(2, cache.entryCount)
        assertEquals(5, cache.contentChars)
    }

    @Test fun oversizedContentDoesNotEvictHealthyEntriesOrChangeTheirRecency() {
        val cache = CompletedMarkdownCache(maxEntries = 2, maxContentChars = 4)
        assertTrue(cache.put("a", success("a"), emptyMap()))
        assertTrue(cache.put("b", success("b"), emptyMap()))
        assertFalse(cache.put("large", success("large"), emptyMap()))
        assertEquals(2, cache.entryCount)
        assertEquals(2, cache.contentChars)
        assertNull(cache.get("large"))

        assertTrue(cache.put("cc", success("cc"), emptyMap()))
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("cc"))
        assertEquals(2, cache.entryCount)
        assertEquals(3, cache.contentChars)
    }

    @Test fun contentExactlyAtTheCharacterLimitIsAccepted() {
        val cache = CompletedMarkdownCache(maxContentChars = 4)
        assertTrue(cache.put("four", success("four"), emptyMap()))
        assertNotNull(cache.get("four"))
        assertEquals(1, cache.entryCount)
        assertEquals(4, cache.contentChars)
    }

    @Test fun zeroBudgetsRejectWithoutAllocatingEntries() {
        val noEntries = CompletedMarkdownCache(maxEntries = 0)
        assertFalse(noEntries.put("", success(""), emptyMap()))
        assertEquals(0, noEntries.entryCount)
        assertEquals(0, noEntries.contentChars)

        val noChars = CompletedMarkdownCache(maxContentChars = 0)
        assertFalse(noChars.put("a", success("a"), emptyMap()))
        assertFalse(noChars.put("", success(""), emptyMap()))
        assertEquals(0, noChars.entryCount)
        assertEquals(0, noChars.contentChars)
    }

    @Test fun cachedRendererWritesCannotContaminateTheCacheOnRepublish() {
        val cache = CompletedMarkdownCache()
        val source = success("[label](destination)")
        assertTrue(cache.put(source.content, source, mapOf("reference" to "original")))
        val first = requireNotNull(cache.get(source.content))
        val rendered = first.state.value as State.Success
        rendered.referenceLinkHandler.store("reference", "overwritten by this renderer")
        rendered.referenceLinkHandler.store("inline", "destination")
        assertTrue(cache.put(source.content, rendered, first.links.value))
        val second = requireNotNull(cache.get(source.content))
        val secondSuccess = second.state.value as State.Success
        assertSame(source.node, secondSuccess.node)
        assertEquals("original", secondSuccess.referenceLinkHandler.find("reference"))
        assertEquals("", secondSuccess.referenceLinkHandler.find("inline"))
        assertEquals(1, cache.entryCount)
    }

    @Test fun entryLimitAlsoBoundsEmptyAndShortDocuments() {
        val cache = CompletedMarkdownCache(maxEntries = 2)
        for (content in listOf("", "a", "b")) assertTrue(cache.put(content, success(content), emptyMap()))
        assertEquals(2, cache.entryCount)
        assertNull(cache.get(""))
        assertEquals(2, cache.contentChars)
    }

    // The parsed tree is reused, but mutable renderer handlers must never be shared.
    private fun assertSameDocument(expected: MarkdownState?, actual: MarkdownState?) {
        val first = requireNotNull(expected).state.value as State.Success
        val second = requireNotNull(actual).state.value as State.Success
        assertNotSame(expected, actual)
        assertSame(first.node, second.node)
        assertEquals(first.content, second.content)
        assertNotSame(first.referenceLinkHandler, second.referenceLinkHandler)
    }

    private fun expectReadOnly(block: () -> Unit) {
        try {
            block()
            fail("A completed snapshot must reject mutation")
        } catch (_: UnsupportedOperationException) {
            // Expected: only the original mutable parsing state may be updated.
        }
    }

    // Test fixtures only; production cache and composition helpers never parse.
    private fun success(content: String, linksLookedUp: Boolean = true): State.Success =
        State.Success(
            node = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content),
            content = content,
            linksLookedUp = linksLookedUp,
            referenceLinkHandler = ReferenceLinkHandlerImpl(),
        )
}
