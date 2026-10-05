package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Differential rendering: identical library host and legacy frozen boundary,
 * with only the candidate's production caller input helper added. This is not
 * a device jank test or a pixel screenshot test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
class FrozenMarkdownRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Frame(val snapshot: StreamingGfmSnapshot, val freeze: Boolean)
    private data class TextResult(
        val text: String,
        val spans: List<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>,
        val paragraphs: List<AnnotatedString.Range<androidx.compose.ui.text.ParagraphStyle>>,
        val links: List<Triple<Int, Int, String>>,
    )

    @Composable
    private fun LegacyBoundary(node: ASTNode, content: String, components: MarkdownComponents, freeze: Boolean) {
        Box {
            if (freeze) {
                val frozenNode = remember { node }
                val frozenContent = remember { content }
                MarkdownElement(node = frozenNode, components = components, content = frozenContent, includeSpacer = false)
            } else {
                MarkdownElement(node = node, components = components, content = content, includeSpacer = false)
            }
        }
    }

    @Composable
    private fun Document(frame: Frame, candidate: Boolean, tag: String, width: androidx.compose.ui.unit.Dp) {
        val components = remember { markdownComponents() }
        val inputState = frame.snapshot.state
        val hostState = remember(inputState) {
            // Direct State.Success hosts bypass MarkdownState's definition lookup.
            // Initialize the CURRENT full source equally for both renderers, while
            // retaining the actual streaming AST/content and shared link handler.
            val initialized = parseMarkdown(
                content = inputState.content,
                lookupLinks = true,
                referenceLinkHandler = inputState.referenceLinkHandler,
            ) as State.Success
            inputState.copy(linksLookedUp = initialized.linksLookedUp)
        }
        Markdown(
            state = hostState,
            modifier = Modifier.width(width).testTag(tag),
            animations = markdownAnimations(animateTextSize = { this }),
            components = components,
            success = { state, components, modifier ->
                Column(modifier) {
                    val node = topLevelMarkdownBlocks(state.node).first()
                    key(node.startOffset, node.type.name) {
                        val renderNode = if (candidate) rememberFrozenMarkdownInput(node, frame.freeze) else node
                        val renderContent = if (candidate) rememberFrozenMarkdownInput(state.content, frame.freeze) else state.content
                        LegacyBoundary(renderNode, renderContent, components, frame.freeze)
                    }
                }
            },
        )
    }

    private fun texts(tag: String): List<TextResult> =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text ->
                    TextResult(
                        text.text, text.spanStyles, text.paragraphStyles,
                        text.getLinkAnnotations(0, text.length).map { link ->
                            val value = when (val item = link.item) {
                                is LinkAnnotation.Url -> item.url
                                is LinkAnnotation.Clickable -> item.tag
                                else -> error("Unrecognized link annotation: $item")
                            }
                            Triple(link.start, link.end, value)
                        },
                    )
                }
            }

    private fun assertSameRendering(label: String) {
        val old = texts("legacy")
        val candidate = texts("candidate")
        assertTrue("real Markdown text must exist: $label", old.isNotEmpty())
        assertEquals("text, inline styles and links: $label", old, candidate)
        val a = compose.onNodeWithTag("legacy").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        assertEquals("width $label", a.right.value - a.left.value, b.right.value - b.left.value, 0.01f)
        assertEquals("height $label", a.bottom.value - a.top.value, b.bottom.value - b.top.value, 0.01f)
    }

    @Test fun realRendererMatchesAcrossFreezeFinalAndTypographyUpdates() {
        val parser = StreamingGfmParserSession()
        fun frame(source: String, freeze: Boolean, complete: Boolean = true) =
            Frame(parser.parse(source, isComplete = complete), freeze)
        val frames = listOf(
            frame("First **bold** and *emphasis* with `code`.", false),
            frame("Second **bold** and [inline guide](https://example.test/inline).\n\nTail", false),
            frame("Freeze-entry **bold** and [reference guide][guide].\n\nTail\n\n[guide]: https://example.test/one", true),
            frame("Correction which must preserve the legacy frozen input.\n\nTail grows\n\n[guide]: https://example.test/two", true),
            frame("Unfrozen **current** and [inline guide](https://example.test/new).\n\nTail", false),
            frame("An unfinished *emphasis", false, complete = false),
            frame("An unfinished *emphasis", false, complete = true),
            frame("A completed paragraph with **styles**, `inline code`, and a [link](https://example.test/last).\n\nTail", true),
        )
        val current = mutableStateOf(frames.first())
        val width = mutableStateOf(320.dp)
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Column {
                        key("legacy") { Document(current.value, false, "legacy", width.value) }
                        key("candidate") { Document(current.value, true, "candidate", width.value) }
                    }
                }
            }
        }
        frames.forEachIndexed { index, frame ->
            compose.runOnIdle { current.value = frame }
            compose.waitForIdle()
            assertSameRendering("frame $index")
            if (index == 2) {
                for (tag in listOf("legacy", "candidate")) {
                    assertTrue("reference annotation must actually exist at freeze entry: $tag",
                        texts(tag).any { text -> text.links.any { it.third == "https://example.test/one" } })
                }
            }
            if (index == 5 || index == 6) {
                assertEquals("tail must use the current projected source", frame.snapshot.renderedSource, frame.snapshot.state.content)
                assertTrue("final/streaming tail text must actually render",
                    texts("candidate").any { it.text.contains("An unfinished") })
                assertEquals(index == 6, frame.snapshot.isComplete)
            }
            if (index == 1 || index == 4) {
                val expectedUrl = if (index == 1) "https://example.test/inline" else "https://example.test/new"
                assertTrue("real link annotations must be rendered in frame $index",
                    texts("candidate").any { text -> text.links.any { it.third == expectedUrl } })
            }
        }
        val before = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        compose.runOnIdle { width.value = 180.dp }
        compose.waitForIdle()
        assertSameRendering("narrow width while frozen")
        val narrow = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        assertTrue("frozen text must still reflow", narrow.bottom - narrow.top > before.bottom - before.top)
        compose.runOnIdle { scale.value = 1.3f; dark.value = true }
        compose.waitForIdle()
        assertSameRendering("font scale and theme while frozen")
        val scaled = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        assertTrue("frozen text must still follow font scale", scaled.bottom - scaled.top > narrow.bottom - narrow.top)
    }
}
