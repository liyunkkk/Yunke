package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real library Markdown collector and callbacks under the same
 * state-identity Host used by production. No test-only keyed collector.
 * Reentry assertions inspect the first committed callback, never merely the
 * eventual state. Layout checks cover dependency rendering, not device jank
 * or the application's custom full-document renderer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
class CompletedMarkdownReentryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initiallyUncachedDocumentReentersWithSuccessOnItsFirstCommittedComposition() {
        val cache = CompletedMarkdownCache()
        assertNull(cache.get(DOCUMENT_A))
        assertEquals(0, cache.entryCount)
        val harness = showDocument(cache)

        // A miss may finish quickly. Do not require observing its initial Loading.
        awaitSuccess(harness, DOCUMENT_A)
        compose.runOnIdle {
            assertCachedSuccess(cache, DOCUMENT_A)
            assertEquals(1, cache.entryCount)
        }

        removeDocument(harness)
        compose.runOnIdle {
            // The owner survives; the child and its remember state do not.
            assertCachedSuccess(cache, DOCUMENT_A)
        }
        reenterDocument(harness)

        // Deliberately no awaitSuccess here. The retained first observation is
        // asserted even if parsing could have completed by the time we inspect it.
        assertImmediateSuccess(harness, DOCUMENT_A)
        compose.onNodeWithText("inline guide", substring = true).assertExists()
    }

    @Test fun changingContentDoesNotReuseThePreviousDocumentsCachedSuccess() {
        val cache = CompletedMarkdownCache()
        val harness = showDocument(cache)
        awaitSuccess(harness, DOCUMENT_A)
        compose.runOnIdle { assertCachedSuccess(cache, DOCUMENT_A) }

        // Enter the cache-hit path for A before changing the content of the same
        // mounted child. Neither the child nor helper is wrapped in key(content).
        removeDocument(harness)
        reenterDocument(harness)
        assertImmediateSuccess(harness, DOCUMENT_A)
        val instanceBeforeChange = compose.runOnIdle {
            assertNull(cache.get(DOCUMENT_B))
            harness.content.value = DOCUMENT_B
            harness.entered.last()
        }
        awaitSuccess(harness, DOCUMENT_B)

        compose.runOnIdle {
            assertEquals(2, harness.entered.size)
            assertEquals(1, harness.disposed.size)
            assertSame(instanceBeforeChange, harness.entered.last())
            val changed = observationsFor(harness, DOCUMENT_B)
            assertTrue("The changed content must actually compose", changed.isNotEmpty())
            // Inspect ALL commits, not just the eventual successful B. A stale A
            // shown on B's first commit must fail even if B later parses correctly.
            changed.forEach { observation ->
                val success = observation.state as? State.Success
                if (success != null) assertSuccess(success, DOCUMENT_B)
            }
            assertCachedSuccess(cache, DOCUMENT_A)
            assertCachedSuccess(cache, DOCUMENT_B)
            assertEquals(2, cache.entryCount)
        }

        removeDocument(harness)
        reenterDocument(harness)
        assertImmediateSuccess(harness, DOCUMENT_B)
    }

    @Test fun missingCacheOwnerStillParsesSuccessfullyAcrossRemounts() {
        // No CompositionLocalProvider at all: exercise the local's null default.
        val harness = showDocument()
        awaitSuccess(harness, DOCUMENT_A)
        compose.runOnIdle {
            assertTrue(harness.observations.isNotEmpty())
            harness.observations.forEach { assertNull(it.owner) }
        }

        removeDocument(harness)
        reenterDocument(harness)
        // Without an owner there is no first-frame cache guarantee. The original
        // asynchronous path must still complete, with no composition exception.
        awaitSuccess(harness, DOCUMENT_A)
        compose.runOnIdle {
            harness.observations.forEach { assertNull(it.owner) }
            assertSuccess(observationsFor(harness, DOCUMENT_A).last().state, DOCUMENT_A)
        }
    }

    @Test fun switchingBetweenTwoCachedDocumentsNeverRendersThePreviousSuccess() {
        val cache = CompletedMarkdownCache()
        for (content in listOf(DOCUMENT_A, DOCUMENT_B)) {
            val parsed = parseMarkdown(content) as State.Success
            val links = if (content == DOCUMENT_A) {
                mapOf("guide" to parsed.referenceLinkHandler.find("guide"))
            } else emptyMap()
            assertTrue(cache.put(content, parsed, links))
        }
        val harness = showDocument(cache)
        assertImmediateSuccess(harness, DOCUMENT_A)
        val originalChild = compose.runOnIdle {
            harness.content.value = DOCUMENT_B
            harness.entered.single()
        }
        compose.waitForIdle()
        assertImmediateSuccess(harness, DOCUMENT_B)
        compose.runOnIdle {
            assertEquals(1, harness.entered.size)
            assertTrue(harness.disposed.isEmpty())
            assertSame(originalChild, harness.entered.single())
        }
    }

    @Test fun cacheHitsStillRemeasureWithWidthAndFontScaleChanges() {
        val content = ("A completed parse must still lay out its text using the current width and font scale. " +
            "Caching syntax must not freeze typography. ").repeat(3)
        val parsed = parseMarkdown(content) as State.Success
        val cache = CompletedMarkdownCache()
        cache.put(content, parsed, emptyMap())
        val harness = showDocument(cache, content)
        assertImmediateSuccess(harness, content)
        val wide = compose.runOnIdle { harness.sizes.last().height }
        compose.runOnIdle { harness.width.value = 220.dp }
        compose.waitForIdle()
        val narrow = compose.runOnIdle { harness.sizes.last().height }
        assertTrue("Narrower width must reflow the cached document", narrow > wide)
        compose.runOnIdle { harness.fontScale.value = 1.3f }
        compose.waitForIdle()
        val larger = compose.runOnIdle { harness.sizes.last().height }
        assertTrue("Larger font scale must remeasure the cached document", larger > narrow)
        compose.runOnIdle {
            harness.observations.forEach { observation ->
                assertSuccess(observation.state, content)
                assertSame(parsed.node, (observation.state as State.Success).node)
            }
        }
    }

    private fun showDocument(
        cache: CompletedMarkdownCache? = null,
        content: String = DOCUMENT_A,
    ): Harness {
        val harness = Harness().apply { this.content.value = content }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, harness.fontScale.value)) {
                MaterialTheme {
                    Box(Modifier.width(harness.width.value)) {
                        if (cache == null) {
                            DocumentHost(harness)
                        } else {
                            CompositionLocalProvider(LocalCompletedMarkdownCache provides cache) {
                                DocumentHost(harness)
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(DOCUMENT_TAG).assertExists()
        return harness
    }

    @Composable
    private fun DocumentHost(harness: Harness) {
        if (harness.visible.value) {
            ObservedDocument(harness.content.value, harness.mount.value, harness)
        }
    }

    @Composable
    private fun ObservedDocument(content: String, mount: Int, harness: Harness) {
        val instance = remember { Any() }
        val owner = LocalCompletedMarkdownCache.current
        val markdownState = rememberCompletedMarkdownState(content)
        DisposableEffect(Unit) {
            harness.entered.add(instance)
            onDispose { harness.disposed.add(instance) }
        }
        Box(Modifier.testTag(DOCUMENT_TAG).onSizeChanged { harness.sizes.add(it) }) {
            CompletedMarkdownStateHost(markdownState) {
                Markdown(
                    markdownState = markdownState,
                    modifier = Modifier.fillMaxWidth(),
                    animations = markdownAnimations(animateTextSize = { this }),
                    loading = { modifier ->
                        RecordObservation(harness, mount, content, State.Loading(), owner)
                        Text(content, modifier = modifier)
                    },
                    error = { throw AssertionError("Markdown parse failed for the test document") },
                    success = { success, components, modifier ->
                        CacheCompletedMarkdownSuccess(content, markdownState, success)
                        RecordObservation(harness, mount, content, success, owner)
                        MarkdownSuccess(success, components, modifier)
                    },
                )
            }
        }
    }

    @Composable
    private fun RecordObservation(
        harness: Harness, mount: Int, content: String, state: State, owner: CompletedMarkdownCache?,
    ) {
        // Capture the actual renderer callback parameter, not a later flow.value.
        SideEffect { harness.observations.add(Observation(mount, content, state, owner)) }
    }

    private fun awaitSuccess(harness: Harness, content: String) {
        val mount = compose.runOnIdle { harness.mount.intValue }
        compose.waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) {
            harness.observations.any { observation ->
                observation.mount == mount && observation.content == content &&
                    (observation.state as? State.Success)?.content == content
            }
        }
        compose.runOnIdle {
            assertSuccess(observationsFor(harness, content).last().state, content)
        }
    }

    private fun removeDocument(harness: Harness) {
        compose.runOnIdle { harness.visible.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(DOCUMENT_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("A real child must have entered before removal", harness.entered.isNotEmpty())
            assertEquals("All child instances must be disposed", harness.entered.size, harness.disposed.size)
            assertSame(harness.entered.last(), harness.disposed.last())
        }
    }

    private fun reenterDocument(harness: Harness) {
        val previousInstance = compose.runOnIdle {
            assertEquals(harness.entered.size, harness.disposed.size)
            harness.entered.last()
        }
        compose.runOnIdle {
            harness.mount.intValue += 1
            harness.visible.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DOCUMENT_TAG).assertExists()
        compose.runOnIdle {
            assertEquals("Exactly one fresh child must be mounted", harness.disposed.size + 1, harness.entered.size)
            assertNotSame("Reentry must not retain the old child remember scope", previousInstance, harness.entered.last())
        }
    }

    private fun assertImmediateSuccess(harness: Harness, content: String) {
        compose.runOnIdle {
            val observations = observationsFor(harness, content)
            assertTrue("Reentry must have a committed observation", observations.isNotEmpty())
            assertSuccess(observations.first().state, content)
            assertTrue("A cache hit must never commit Loading", observations.none { it.state is State.Loading })
            observations.forEach { assertSuccess(it.state, content) }
        }
    }

    // Called only on the Compose/UI thread; waitUntil reads the thread-safe log.
    private fun observationsFor(harness: Harness, content: String): List<Observation> =
        harness.observations.filter { it.mount == harness.mount.intValue && it.content == content }

    private fun assertCachedSuccess(cache: CompletedMarkdownCache, content: String) {
        val cached = cache.get(content)
        assertTrue("The actual success SideEffect must populate the owner cache", cached != null)
        assertSuccess(requireNotNull(cached).state.value, content)
    }

    private fun assertSuccess(state: State, expectedContent: String) {
        assertTrue("Expected immediate Success for the requested full document, got $state", state is State.Success)
        val success = state as State.Success
        assertEquals(expectedContent, success.content)
        assertEquals("The successful AST must cover the complete source", expectedContent.length, success.node.endOffset)
    }

    private class Harness {
        val visible = mutableStateOf(true)
        val content = mutableStateOf(DOCUMENT_A)
        val mount = mutableIntStateOf(0)
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        val sizes = CopyOnWriteArrayList<IntSize>()
        val entered = CopyOnWriteArrayList<Any>()
        val disposed = CopyOnWriteArrayList<Any>()
        val observations = CopyOnWriteArrayList<Observation>()
    }

    private data class Observation(
        val mount: Int,
        val content: String,
        val state: State,
        val owner: CompletedMarkdownCache?,
    )

    private companion object {
        const val DOCUMENT_TAG = "completed-markdown-child"
        const val PARSE_TIMEOUT_MILLIS = 15_000L

        val DOCUMENT_A = """
            # Completed answer A

            This paragraph has **bold text**, *emphasis*, and `inline code`.
            The complete answer must survive removal from its owner's composition.

            ## Details

            - First completed item
            - Second completed item

            > A completed quotation remains part of the document.

            ```kotlin
            val answer = 42
            println(answer)
            ```

            | Field | Value |
            | --- | --- |
            | status | complete |

            See the [inline guide](https://example.test/guide), <https://example.test/automatic>,
            and the [reference guide][guide] without changing the parsed document.

            [guide]: https://example.test/reference

            Final paragraph A: this is the end of the full document.
        """.trimIndent()

        val DOCUMENT_B = """
            # Different answer B

            This content is not answer A, even though the mounted child is reused.

            1. A distinct first item
            2. A distinct second item

            **Final paragraph B** must come from B's own successful parse.
        """.trimIndent()
    }
}
