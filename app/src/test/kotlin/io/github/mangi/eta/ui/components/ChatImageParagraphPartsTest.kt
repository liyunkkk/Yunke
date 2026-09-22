package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import org.junit.Assert.*
import org.junit.Test

class ChatImageParagraphPartsTest {
    private fun AnnotatedString.Builder.image(url: String) = appendInlineContent("${MARKDOWN_TAG_IMAGE_URL}_$url", url)
    @Test fun hardBreakCaptionCannotBeReplacedByItsImage() {
        val input = buildAnnotatedString { append("1:1  \n"); image("/cache/one.png"); append("\n后面的说明") }
        val parts = chatImageParagraphParts(input)
        assertEquals(3, parts.size)
        assertEquals("1:1  \n", (parts[0] as ChatImageParagraphPart.Text).text.text)
        assertEquals("/cache/one.png", (parts[1] as ChatImageParagraphPart.Image).source)
        assertEquals("\n后面的说明", (parts[2] as ChatImageParagraphPart.Text).text.text)
    }
    @Test fun repeatedSourcesAreNotDeduplicatedOrUsedToLocateText() {
        val parts = chatImageParagraphParts(buildAnnotatedString {
            append("/same.png caption "); image("/same.png"); append("\n"); image("/same.png"); append(" end")
        })
        assertEquals(2, parts.filterIsInstance<ChatImageParagraphPart.Image>().size)
        assertEquals("/same.png caption ", (parts.first() as ChatImageParagraphPart.Text).text.text)
        assertEquals(" end", (parts.last() as ChatImageParagraphPart.Text).text.text)
    }
    @Test fun annotationsAndLinksAreSlicedWithoutFlatteningOrOffsetDamage() {
        val original = buildAnnotatedString {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append("前文"); pop()
            image("/cache/picture.png")
            pushLink(LinkAnnotation.Url("https://example.invalid")); append("后文链接"); pop()
        }
        val parts = chatImageParagraphParts(original)
        val before = (parts[0] as ChatImageParagraphPart.Text).text
        val after = (parts[2] as ChatImageParagraphPart.Text).text
        assertEquals(FontWeight.Bold, before.spanStyles.single().item.fontWeight)
        val link = after.getLinkAnnotations(0, after.length).single()
        assertEquals(0, link.start); assertEquals(after.length, link.end)
        assertEquals("https://example.invalid", (link.item as LinkAnnotation.Url).url)
    }
    @Test fun firstLastConsecutiveAndLinkedImageKeepOrder() {
        val parts = chatImageParagraphParts(buildAnnotatedString {
            pushLink(LinkAnnotation.Url("https://example.invalid/outer")); image("/first.png"); pop()
            image("/second.png"); append("尾部"); image("/third.png")
        })
        assertEquals(listOf("/first.png","/second.png","/third.png"),parts.filterIsInstance<ChatImageParagraphPart.Image>().map { it.source })
        assertEquals("尾部", parts.filterIsInstance<ChatImageParagraphPart.Text>().single().text.text)
    }
    @Test fun literalImageSyntaxAndIncompleteStreamAreStillText() {
        for (raw in listOf("`![example](url)`", "before ![image](", " text\n\n with spaces  ")) {
            val parts = chatImageParagraphParts(AnnotatedString(raw))
            assertEquals(listOf(ChatImageParagraphPart.Text(AnnotatedString(raw))), parts)
        }
    }
}
