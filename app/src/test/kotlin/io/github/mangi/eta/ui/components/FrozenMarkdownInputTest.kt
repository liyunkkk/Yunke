package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FrozenMarkdownInputTest {
    @get:Rule val compose = createComposeRule()

    private data class Frame(
        val snapshot: StreamingGfmSnapshot,
        val freeze: Boolean,
        val mounted: Boolean = true,
    )
    private data class Input(val node: ASTNode, val content: String)

    // The legacy renderer's Box and conditional capture are deliberately retained.
    @Composable
    private fun ExistingBoundary(node: ASTNode, content: String, freeze: Boolean, observe: (Input) -> Unit) {
        Box {
            if (freeze) {
                val frozenNode = remember { node }
                val frozenContent = remember { content }
                SideEffect { observe(Input(frozenNode, frozenContent)) }
            } else {
                SideEffect { observe(Input(node, content)) }
            }
        }
    }

    @Composable
    private fun Host(frame: Frame, candidate: Boolean, observe: (Input) -> Unit) {
        if (!frame.mounted) return
        val node = topLevelMarkdownBlocks(frame.snapshot.state.node).first()
        val content = frame.snapshot.state.content
        key(node.startOffset, node.type.name) {
            // Always keep ExistingBoundary at one call site, just as production does.
            val renderNode = if (candidate) rememberFrozenMarkdownInput(node, frame.freeze) else node
            val renderContent = if (candidate) rememberFrozenMarkdownInput(content, frame.freeze) else content
            ExistingBoundary(renderNode, renderContent, frame.freeze, observe)
        }
    }

    @Test fun callerPinningMatchesExistingCaptureResetAndKeyLifetimes() {
        val parser = StreamingGfmParserSession()
        fun frame(source: String, freeze: Boolean, mounted: Boolean = true) =
            Frame(parser.parse(source, isComplete = true), freeze, mounted)
        val frames = listOf(
            frame("A **one**.\n\nTail", false),
            frame("B **two**.\n\nTail grows", false),
            frame("C **three**.\n\nNext", true),
            frame("D **four**.\n\nNext grows", true),
            frame("E **five**.\n\nTail", false),
            frame("F **six**.\n\nNext", true),
            frame("G **seven**.\n\nNext grows", true),
            frame("removed", true, mounted = false),
            frame("H **eight**.\n\nNext", true),
            frame("# Heading nine\n\nNext", true),
            frame("# Heading ten\n\nNext grows", true),
            frame("Paragraph eleven.\n\nNext", true),
            // Same type but changed startOffset must get a fresh composition key.
            frame("\n\nParagraph twelve.\n\nNext", true),
        )
        val expectedCapture = listOf(0, 1, 2, 2, 4, 5, 5, null, 8, 9, 9, 11, 12)
        val current = mutableStateOf(frames.first())
        val observed = arrayOfNulls<Input>(2)
        compose.setContent {
            Column {
                key("legacy") { Host(current.value, false) { observed[0] = it } }
                key("candidate") { Host(current.value, true) { observed[1] = it } }
            }
        }
        frames.indices.forEach { index ->
            compose.runOnIdle { current.value = frames[index] }
            compose.waitForIdle()
            val captureIndex = expectedCapture[index] ?: return@forEach
            compose.runOnIdle {
                val expected = frames[captureIndex].snapshot
                val legacy = checkNotNull(observed[0])
                val candidate = checkNotNull(observed[1])
                assertSame("legacy frame $index", topLevelMarkdownBlocks(expected.state.node).first(), legacy.node)
                assertSame("candidate AST frame $index", legacy.node, candidate.node)
                assertSame("legacy source frame $index", expected.state.content, legacy.content)
                assertSame("candidate source frame $index", legacy.content, candidate.content)
            }
        }
    }

    private class ProbeLog {
        var calls = 0
        var input: Input? = null
        var components: MarkdownComponents? = null
        var environment = -1
    }
    private val LocalEnvironment = staticCompositionLocalOf { 0 }

    @Composable
    private fun RenderProbe(node: ASTNode, content: String, components: MarkdownComponents, log: ProbeLog) {
        val environment = LocalEnvironment.current
        SideEffect {
            log.calls++
            log.input = Input(node, content)
            log.components = components
            log.environment = environment
        }
    }

    @Test fun frozenAstSkipsUnchangedRendererButNotTailComponentsOrCurrentLocals() {
        val parser = StreamingGfmParserSession()
        fun snapshot(tail: String) = parser.parse("Frozen **paragraph**.\n\n$tail", isComplete = true)
        val current = mutableStateOf(snapshot("Tail one"))
        val freeze = mutableStateOf(true)
        val components = mutableStateOf(markdownComponents())
        val environment = mutableStateOf(0)
        val legacy = ProbeLog()
        val candidate = ProbeLog()
        compose.setContent {
            CompositionLocalProvider(LocalEnvironment provides environment.value) {
                Column {
                    val node = topLevelMarkdownBlocks(current.value.state.node).first()
                    val source = current.value.state.content
                    key("legacy", node.startOffset, node.type.name) {
                        RenderProbe(node, source, components.value, legacy)
                    }
                    key("candidate", node.startOffset, node.type.name) {
                        val renderNode = rememberFrozenMarkdownInput(node, freeze.value)
                        val renderContent = rememberFrozenMarkdownInput(source, freeze.value)
                        RenderProbe(renderNode, renderContent, components.value, candidate)
                    }
                }
            }
        }
        var baseline = 0
        var oldCalls = 0
        val captured = compose.runOnIdle {
            baseline = candidate.calls
            oldCalls = legacy.calls
            checkNotNull(candidate.input)
        }
        repeat(8) { i ->
            compose.runOnIdle {
                val next = snapshot("Tail grows $i")
                assertNotSame("parser must provide a new AST to the control",
                    topLevelMarkdownBlocks(current.value.state.node).first(),
                    topLevelMarkdownBlocks(next.state.node).first())
                current.value = next
            }
            compose.runOnIdle {
                assertEquals("frozen renderer must skip delta $i", baseline, candidate.calls)
                assertTrue("control must actually compose delta $i", legacy.calls > oldCalls)
                oldCalls = legacy.calls
                assertSame(captured.node, candidate.input!!.node)
                assertSame(captured.content, candidate.input!!.content)
            }
        }
        compose.runOnIdle { freeze.value = false }
        compose.runOnIdle {
            assertTrue(candidate.calls > baseline)
            assertSame(topLevelMarkdownBlocks(current.value.state.node).first(), candidate.input!!.node)
            baseline = candidate.calls
            current.value = snapshot("Current tail after unfreeze")
        }
        compose.runOnIdle {
            assertTrue("unfrozen input must update", candidate.calls > baseline)
            assertSame(current.value.state.content, candidate.input!!.content)
            freeze.value = true
        }
        compose.runOnIdle {
            assertSame("refreeze captures current AST", topLevelMarkdownBlocks(current.value.state.node).first(), candidate.input!!.node)
            assertSame("refreeze captures current complete source", current.value.state.content, candidate.input!!.content)
            baseline = candidate.calls
            current.value = snapshot("Tail after the second freeze")
        }
        compose.runOnIdle {
            assertEquals("refrozen renderer must also skip the next delta", baseline, candidate.calls)
            assertEquals("Frozen **paragraph**.\n\nCurrent tail after unfreeze", candidate.input!!.content)
            // A genuine component change must reach even an already frozen block.
            components.value = markdownComponents(paragraph = { _ -> Box {} })
        }
        compose.runOnIdle {
            assertTrue("component changes must not be pinned", candidate.calls > baseline)
            assertSame(components.value, candidate.components)
            baseline = candidate.calls
            environment.value = 1
        }
        compose.runOnIdle {
            assertTrue("current CompositionLocal must invalidate the renderer", candidate.calls > baseline)
            assertEquals(1, candidate.environment)
        }
    }
}
