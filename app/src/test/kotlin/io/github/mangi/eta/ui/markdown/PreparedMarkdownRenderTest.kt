package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.junit.Assert.*
import org.junit.Test

class PreparedMarkdownRenderTest {
    private fun spec(color: Color = Color.Black, listener: LinkInteractionListener? = null): PreparedMarkdownSpec {
        val body = TextStyle(color = color, fontSize = 16.sp)
        val links = TextLinkStyles(style = SpanStyle(color = Color.Blue))
        val type = DefaultMarkdownTypography(
            h1 = body.copy(fontSize = 21.sp), h2 = body.copy(fontSize = 19.sp),
            h3 = body, h4 = body, h5 = body, h6 = body,
            text = body, code = body, inlineCode = body, quote = body,
            paragraph = body, ordered = body, bullet = body, list = body,
            textLink = links, table = body.copy(fontSize = 14.sp),
        )
        return PreparedMarkdownSpec(type, body.toSpanStyle(), links, listener)
    }

    private fun legacy(block: PreparedMarkdownBlock, node: ASTNode, style: SpanStyle): AnnotatedString {
        val spec = requireNotNull(block.spec)
        return buildAnnotatedString {
            pushStyle(style)
            buildMarkdownAnnotatedString(block.source, node,
                DefaultAnnotatorSettings(spec.links, spec.inlineCode, spec.annotator, null, spec.listener))
            pop()
        }
    }

    @Test fun appendReusesFinishedBlockAndSourceButReplacesTail() {
        val session = StreamingGfmParserSession()
        val spec = spec()
        val first = session.parse("Stable **paragraph**.\n\nTail", false, spec)
        val next = session.parse("Stable **paragraph**.\n\nTail grows", false, spec)
        assertSame(first.preparedBlocks.first(), next.preparedBlocks.first())
        assertSame(first.preparedBlocks.first().node, next.preparedBlocks.first().node)
        assertSame(first.preparedBlocks.first().source, next.preparedBlocks.first().source)
        assertNotSame(first.preparedBlocks.last(), next.preparedBlocks.last())
        assertEquals("Tail grows", next.preparedBlocks.last().text(next.preparedBlocks.last().node, spec.typography.paragraph.toSpanStyle())?.text)
        assertEquals("Stable **paragraph**.\n\nTail", first.originalSource)
    }

    @Test fun preparedParagraphMatchesOriginalAnnotatorIncludingStyles() {
        val spec = spec()
        val block = StreamingGfmParserSession().parse("Plain **bold** *italic* `code` &amp; text.", false, spec).preparedBlocks.first()
        val style = spec.typography.paragraph.toSpanStyle()
        assertEquals(legacy(block, block.node, style), block.text(block.node, style))
        assertNotNull(block.text(block.node, style))
    }

    @Test fun preparedHeadingUsesTheExactContentChildAndHeadingStyle() {
        val spec = spec()
        val block = StreamingGfmParserSession().parse("# Strong **heading**", false, spec).preparedBlocks.first()
        val child = requireNotNull(block.node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT))
        val style = spec.typography.h1.toSpanStyle()
        assertEquals(legacy(block, child, style), block.text(child, style))
        assertNotNull(block.text(child, style))
        assertNull(block.text(child, spec.typography.paragraph.toSpanStyle()))
    }

    @Test fun tableCellsPrepareBodyAndBoldHeaderStylesWithoutChangingLayout() {
        val spec = spec()
        val block = StreamingGfmParserSession().parse("| **A** | B |\n| --- | --- |\n| one | `two` |", false, spec).preparedBlocks.first()
        fun cells(node: ASTNode): List<ASTNode> = if (node.type == CELL) listOf(node) else node.children.flatMap(::cells)
        val list = cells(block.node)
        assertEquals(4, list.size)
        list.forEach { cell ->
            val body = spec.typography.table.toSpanStyle()
            val header = spec.typography.table.copy(fontWeight = FontWeight.SemiBold).toSpanStyle()
            assertEquals(legacy(block, cell, body), block.text(cell, body))
            assertEquals(legacy(block, cell, header), block.text(cell, header))
            assertNotNull(block.text(cell, body))
        }
    }

    @Test fun referencesAndFileImagesKeepCurrentSourceAndRendererFallback() {
        val session = StreamingGfmParserSession()
        val spec = spec()
        val a = session.parse("[guide][g]\n\nTail\n\n[g]: https://one.example", false, spec)
        val b = session.parse("[guide][g]\n\nTail grows\n\n[g]: https://two.example", false, spec)
        assertNotSame(a.preparedBlocks.first(), b.preparedBlocks.first())
        assertEquals(b.renderedSource, b.preparedBlocks.first().source)
        assertNull(b.preparedBlocks.first().text(b.preparedBlocks.first().node, spec.typography.paragraph.toSpanStyle()))
        val image = session.parse("![photo](content://images/one)", false, spec).preparedBlocks.first()
        assertNull(image.text(image.node, spec.typography.paragraph.toSpanStyle()))
    }

    @Test fun themeAndLinkListenerChangesInvalidatePreparedBlocks() {
        val session = StreamingGfmParserSession()
        val firstSpec = spec()
        val a = session.parse("Same **text**", false, firstSpec)
        val b = session.parse("Same **text**", false, spec(Color.Red))
        assertNotSame(a.preparedBlocks.first(), b.preparedBlocks.first())
        assertSame(b, nextStreamingSnapshot(a, b))
        val c = session.parse("Same **text**", false, spec(Color.Red, LinkInteractionListener { }))
        assertNotSame(b.preparedBlocks.first(), c.preparedBlocks.first())
        assertSame(c, nextStreamingSnapshot(b, c))
    }

    @Test fun samePreparedTargetIsNoOpButTerminalTargetPublishesFreshInputs() {
        val session = StreamingGfmParserSession()
        val spec = spec()
        val a = session.parse("Finished **text**", false, spec)
        val repeated = session.parse("Finished **text**", false, spec)
        assertSame(a.preparedBlocks.first(), repeated.preparedBlocks.first())
        assertNull(nextStreamingSnapshot(a, repeated))
        val terminal = session.parse("Finished **text**", true, spec)
        assertSame(terminal, nextStreamingSnapshot(a, terminal))
        assertNotSame(a.preparedBlocks.first(), terminal.preparedBlocks.first())
        assertTrue(terminal.isComplete)
        assertEquals(terminal.originalSource, terminal.renderedSource)
    }

    @Test fun correctionsAndReopenedTerminalNeverReuseAnOlderDocument() {
        val session = StreamingGfmParserSession()
        val spec = spec()
        val a = session.parse("Stable.\n\nOld tail", false, spec)
        val correction = session.parse("Stable.\n\nNew tail", false, spec)
        assertNotSame(a.preparedBlocks.first(), correction.preparedBlocks.first())
        val terminal = session.parse(correction.originalSource, true, spec)
        val reopened = session.parse(correction.originalSource, false, spec)
        assertNotSame(terminal.preparedBlocks.first(), reopened.preparedBlocks.first())
        assertSame(reopened, nextStreamingSnapshot(terminal, reopened))
    }

    @Test fun changedBlockStructureCannotUseAnOldPreparedNode() {
        val session = StreamingGfmParserSession()
        val spec = spec()
        val paragraph = session.parse("Heading", false, spec)
        val heading = session.parse("Heading\n===", false, spec)
        assertNotSame(paragraph.preparedBlocks.first(), heading.preparedBlocks.first())
        assertNotEquals(paragraph.preparedBlocks.first().cacheKey, heading.preparedBlocks.first().cacheKey)
    }

    @Test fun retainedOldSourceBudgetRebuildsInsteadOfLeakingSnapshots() {
        val parser = StreamingGfmParserSession()
        val cache = PreparedMarkdownSession(maxRetainedSourceChars = 0)
        val a = parser.parse("Stable.\n\nTail", false)
        val b = parser.parse("Stable.\n\nTail grows", false)
        val first = cache.prepare(a.state.node, a.renderedSource, null)
        val next = cache.prepare(b.state.node, b.renderedSource, null)
        assertNotSame(first.first(), next.first())
        assertSame(b.renderedSource, next.first().source)
        assertEquals("Stable.\n\nTail", first.first().source)
    }

    @Test fun annotatorIsCapturedAndParticipatesInBlockIdentity() {
        val session = StreamingGfmParserSession()
        val originalSpec = spec()
        val original = session.parse("Same text", false, originalSpec)
        val custom = markdownAnnotator(annotate = { _, _ -> false })
        val changed = session.parse("Same text", false, originalSpec.copy(annotator = custom))
        assertSame(custom, changed.renderSpec?.annotator)
        assertNotSame(original.preparedBlocks.first(), changed.preparedBlocks.first())
        assertSame(changed, nextStreamingSnapshot(original, changed))
        val block = changed.preparedBlocks.first()
        val style = originalSpec.typography.paragraph.toSpanStyle()
        assertEquals(legacy(block, block.node, style), block.text(block.node, style))
    }

    @Test fun emptyAndEolOnlyDocumentsPublishSpecAndTerminalChanges() {
        for (text in listOf("", "\n\n")) {
            val session = StreamingGfmParserSession()
            val initial = session.parse(text, false, spec())
            assertTrue(initial.preparedBlocks.isEmpty())
            val changed = session.parse(text, false, spec(Color.Red))
            assertSame(changed, nextStreamingSnapshot(initial, changed))
            assertNull(nextStreamingSnapshot(changed, session.parse(text, false, changed.renderSpec)))
            val terminal = session.parse(text, true, changed.renderSpec)
            assertSame(terminal, nextStreamingSnapshot(changed, terminal))
            assertTrue(terminal.isComplete)
        }
    }

    @Test fun noPreparedSpecKeepsOriginalSameSourceTerminalIdentityContract() {
        val parser = StreamingGfmParserSession()
        val a = parser.parse("text", false)
        val terminal = parser.parse("text", true)
        assertSame(a.state, nextStreamingSnapshot(a, terminal)?.state)
    }
}
