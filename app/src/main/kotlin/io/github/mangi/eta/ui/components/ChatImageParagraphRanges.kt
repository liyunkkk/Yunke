package io.github.mangi.eta.ui.components

/** A null source denotes text; image ranges refer to rendered annotation offsets, not raw Markdown. */
internal data class ChatImageParagraphRange(val start: Int, val end: Int, val source: String? = null)

/** Keep all meaningful text exactly once; media never consumes an adjacent caption. */
internal fun chatImageParagraphRanges(
    text: String,
    imageRanges: List<ChatImageParagraphRange>,
): List<ChatImageParagraphRange> {
    val images = imageRanges.filter {
        !it.source.isNullOrBlank() && it.start >= 0 && it.start < it.end && it.end <= text.length
    }.distinct().sortedBy { it.start }
    val parts = mutableListOf<ChatImageParagraphRange>()
    fun addText(start: Int, end: Int) {
        // Do not trim text or lose the offsets used to slice styles/links. Only a standalone
        // whitespace separator between images is replaced by normal block spacing.
        if (start < end && text.substring(start, end).isNotBlank())
            parts += ChatImageParagraphRange(start, end)
    }
    var cursor = 0
    images.forEach { range ->
        if (range.start >= cursor) {
            addText(cursor, range.start)
            parts += range
            cursor = range.end
        }
    }
    addText(cursor, text.length)
    return parts
}
