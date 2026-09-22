package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class ChatImageParagraphRangesTest {
    private fun texts(text: String, parts: List<ChatImageParagraphRange>) =
        parts.filter { it.source == null }.map { text.substring(it.start,it.end) }

    @Test fun hardBreakCaptionAndTailRetainExactOffsets() {
        val before="9:16  \n"; val image="/cache/image.png"; val after="\n图片之后的文字"
        val text=before+image+after
        val range=ChatImageParagraphRange(before.length,before.length+image.length,image)
        val parts=chatImageParagraphRanges(text,listOf(range))
        assertEquals(listOf(before,after),texts(text,parts))
        assertEquals(range,parts[1])
        assertEquals(3,parts.size)
    }
    @Test fun fourCaptionsAndImagesRemainInSourceOrder() {
        val text=StringBuilder(); val images=mutableListOf<ChatImageParagraphRange>()
        val captions=listOf("1:1","9:16","16:9","1:2 请求——实际返回近似 9:16")
        captions.forEachIndexed { i, caption ->
            text.append(caption).append("  \n")
            val start=text.length;val source="/cache/$i.png";text.append(source)
            images+=ChatImageParagraphRange(start,text.length,source)
            text.append("\n\n")
        }
        val parts=chatImageParagraphRanges(text.toString(),images)
        assertEquals(8,parts.size)
        assertEquals(images,parts.filter { it.source != null })
        assertEquals(captions,texts(text.toString(),parts).map(String::trim))
    }
    @Test fun sameUrlInCaptionIsNotMistakenForTheImage() {
        val source="/same.png";val text="$source caption $source middle $source tail"
        val first=text.indexOf(source,source.length); val second=text.indexOf(source,first+source.length)
        val parts=chatImageParagraphRanges(text,listOf(
            ChatImageParagraphRange(first,first+source.length,source),ChatImageParagraphRange(second,second+source.length,source)))
        assertEquals(listOf("$source caption "," middle "," tail"),texts(text,parts))
        assertEquals(2,parts.count { it.source != null })
    }
    @Test fun consecutiveAndEdgeImagesDoNotCreateFakeTextRows() {
        val parts=chatImageParagraphRanges("abc",listOf(
            ChatImageParagraphRange(0,1,"a"),ChatImageParagraphRange(1,2,"b"),ChatImageParagraphRange(2,3,"c")))
        assertEquals(listOf("a","b","c"),parts.map { it.source })
    }
    @Test fun duplicatesAreDeduplicatedButSeparateOccurrencesAreNot() {
        val first=ChatImageParagraphRange(0,1,"same");val second=ChatImageParagraphRange(2,3,"same")
        assertEquals(listOf(first,second),chatImageParagraphRanges("a b",listOf(second,first,first)))
    }
    @Test fun malformedRangesCannotEatTextOrCrashSubSequence() {
        val text="all text must survive"
        val invalid=listOf(ChatImageParagraphRange(-1,2,"x"),ChatImageParagraphRange(0,999,"y"),
            ChatImageParagraphRange(2,2,"z"),ChatImageParagraphRange(3,1,"z"),ChatImageParagraphRange(0,1,""))
        assertEquals(listOf(text),texts(text,chatImageParagraphRanges(text,invalid)))
    }
    @Test fun overlappingAnnotationsNeverDuplicateCoveredCharacters() {
        val image=ChatImageParagraphRange(1,3,"first")
        val parts=chatImageParagraphRanges("abcde",listOf(image,ChatImageParagraphRange(2,4,"overlap")))
        assertEquals(listOf(ChatImageParagraphRange(0,1),image,ChatImageParagraphRange(3,5)),parts)
    }
    @Test fun incompleteMarkdownCodeAndWhitespaceInsideTextArePreserved() {
        for(text in listOf("before ![image](","`![literal](url)`","  text\n\nmore  ")) {
            assertEquals(listOf(text),texts(text,chatImageParagraphRanges(text,emptyList())))
        }
        assertTrue(chatImageParagraphRanges("\n  ",emptyList()).isEmpty())
    }
}
