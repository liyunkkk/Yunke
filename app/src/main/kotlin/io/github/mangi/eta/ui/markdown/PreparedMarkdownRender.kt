package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import java.util.Collections
import java.util.IdentityHashMap

/** Captured on the UI thread, consumed serially on Default. No mutable link handler is shared.
 * Reference/image-bearing blocks keep the original renderer path and current document dependency.
 * This adapts upstream 4c891011's prepared-document/block-reuse mechanism, not its UI policies.
 */
internal data class PreparedMarkdownSpec(
    val typography: MarkdownTypography,
    val inlineCode: SpanStyle,
    val links: TextLinkStyles,
    val listener: LinkInteractionListener?,
    val annotator: MarkdownAnnotator = markdownAnnotator(),
)

internal class PreparedMarkdownBlock(
    val node: ASTNode,
    val source: String,
    val cacheKey: MarkdownRenderCacheKey,
    val spec: PreparedMarkdownSpec?,
    private val texts: Map<ASTNode, List<Pair<SpanStyle?, AnnotatedString>>>,
) {
    fun text(node: ASTNode, style: SpanStyle?): AnnotatedString? =
        texts[node]?.firstOrNull { it.first == style }?.second
}

internal val LocalPreparedMarkdownBlock = staticCompositionLocalOf<PreparedMarkdownBlock?> { null }

/** Serial parser-session cache. Published blocks/maps are never mutated or labelled Stable.
 * Only the latest document is retained; distinct retained source snapshots have a character cap.
 */
internal class PreparedMarkdownSession(private val maxRetainedSourceChars: Int = 262144) {
    private var previous = emptyMap<Int, PreparedMarkdownBlock>()
    fun clear() { previous = emptyMap() }

    fun prepare(root: ASTNode, source: String, spec: PreparedMarkdownSpec?): List<PreparedMarkdownBlock> {
        val retainedSources = Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
        var retainedChars = 0L
        val next = LinkedHashMap<Int, PreparedMarkdownBlock>()
        val blocks = root.children.filterNot { it.type == MarkdownTokenTypes.EOL }.map { node ->
            val key = markdownRenderCacheKey(source, node)
            val candidate = previous[node.startOffset]?.takeIf { it.cacheKey == key && it.spec == spec }
            val canRetain = candidate != null && (candidate.source === source ||
                candidate.source in retainedSources ||
                retainedChars + candidate.source.length + source.length <= maxRetainedSourceChars)
            val block = if (canRetain) candidate!! else PreparedMarkdownBlock(
                node, source, key, spec, prepareTexts(node, source, spec),
            )
            if (block.source !== source && retainedSources.add(block.source)) retainedChars += block.source.length
            next[node.startOffset] = block
            block
        }
        previous = next
        return blocks
    }

    private fun prepareTexts(root: ASTNode, source: String, spec: PreparedMarkdownSpec?): Map<ASTNode, List<Pair<SpanStyle?, AnnotatedString>>> {
        if (spec == null || '[' in source.substring(root.startOffset, root.endOffset)) return emptyMap()
        val output = IdentityHashMap<ASTNode, List<Pair<SpanStyle?, AnnotatedString>>>()
        val settings = DefaultAnnotatorSettings(spec.links, spec.inlineCode, spec.annotator, null, spec.listener)
        fun annotated(node: ASTNode, style: SpanStyle) {
            val text = StreamPerformanceDiagnostics.measure("markdown.prepared.annotated", (node.endOffset - node.startOffset).toLong()) {
                buildAnnotatedString {
                    pushStyle(style)
                    buildMarkdownAnnotatedString(content = source, node = node, annotatorSettings = settings)
                    pop()
                }
            }
            output[node] = output[node].orEmpty() + (style to text)
        }
        fun visit(node: ASTNode) {
            val t = spec.typography
            when (node.type) {
                MarkdownElementTypes.PARAGRAPH -> annotated(node, t.paragraph.toSpanStyle())
                MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
                MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
                MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> {
                    val style = when (node.type) {
                        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> t.h1
                        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> t.h2
                        MarkdownElementTypes.ATX_3 -> t.h3
                        MarkdownElementTypes.ATX_4 -> t.h4
                        MarkdownElementTypes.ATX_5 -> t.h5
                        else -> t.h6
                    }
                    val childType = if (node.type == MarkdownElementTypes.SETEXT_1 || node.type == MarkdownElementTypes.SETEXT_2)
                        MarkdownTokenTypes.SETEXT_CONTENT else MarkdownTokenTypes.ATX_CONTENT
                    annotated(node.findChildOfType(childType) ?: node, style.toSpanStyle())
                }
                CELL -> {
                    annotated(node, t.table.toSpanStyle())
                    annotated(node, t.table.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold).toSpanStyle())
                }
                MarkdownTokenTypes.TEXT -> output[node] = listOf(null to
                    StreamPerformanceDiagnostics.measure("markdown.prepared.raw", (node.endOffset - node.startOffset).toLong()) {
                        AnnotatedString(node.getUnescapedTextInNode(source))
                    })
            }
            // Preserve the existing renderer for code/image/HTML and unsupported nodes.
            if (node.type !in preparedLeafTypes) node.children.forEach(::visit)
        }
        visit(root)
        return Collections.unmodifiableMap(output)
    }

    private val preparedLeafTypes = setOf(
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
        MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, CELL,
    )
}
