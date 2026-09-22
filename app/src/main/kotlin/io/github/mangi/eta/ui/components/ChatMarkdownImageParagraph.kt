package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import io.github.mangi.eta.ui.markdown.ChatSelectableText
import io.github.mangi.eta.ui.markdown.markdownRenderCacheKey
import org.intellij.markdown.ast.ASTNode

internal sealed interface ChatImageParagraphPart {
    data class Text(val text: AnnotatedString) : ChatImageParagraphPart
    data class Image(val source: String) : ChatImageParagraphPart
}

/** Split rendered annotations, not Markdown syntax: styles/links and reference resolution survive. */
internal fun chatImageParagraphParts(text: AnnotatedString): List<ChatImageParagraphPart> {
    val prefix = "${MARKDOWN_TAG_IMAGE_URL}_"
    val images = text.getStringAnnotations(0, text.length)
        .filter { it.item.startsWith(prefix) }
        .map { ChatImageParagraphRange(it.start, it.end, it.item.removePrefix(prefix)) }
    return chatImageParagraphRanges(text.text, images).map { range ->
        range.source?.let { ChatImageParagraphPart.Image(it) }
            ?: ChatImageParagraphPart.Text(text.subSequence(range.start, range.end))
    }
}

/** Captions and media get independent measured blocks even before the image has loaded. */
@Composable
internal fun ChatMarkdownImageParagraph(
    content: String,
    node: ASTNode,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val settings = annotatorSettings()
    val annotated = remember(markdownRenderCacheKey(content, node), style, settings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(content, node, annotatorSettings = settings)
            pop()
        }
    }
    val parts = remember(annotated) { chatImageParagraphParts(annotated) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is ChatImageParagraphPart.Text -> ChatSelectableText(part.text, style)
                is ChatImageParagraphPart.Image -> ChatMarkdownImage(content, node, sourceOverride = part.source)
            }
        }
    }
}
