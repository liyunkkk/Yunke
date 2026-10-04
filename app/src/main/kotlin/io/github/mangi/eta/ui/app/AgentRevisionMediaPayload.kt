package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.UserMessageUi
import org.json.JSONArray

/** Producer-specific comparison only; callers must prove the same nonempty turn owner. */
internal object AgentRevisionMediaPayload {
    private const val OMITTED = "[图片观察已在当前回合使用，未写入持久会话]"

    fun matches(history: AgentModelClient.ConversationMessage, user: UserMessageUi,
                normalize: (String) -> String, visibleText: String = user.content): Boolean {
        if (user.images.isEmpty() || history.contentJson.isBlank()) return false
        val sources = user.imageSources.ifEmpty { user.images }
        if (sources.size != user.images.size) return false
        val paths = sources.map { it.removePrefix("file://") }
        if (paths.any { !it.startsWith('/') || it.contains('\n') || it.contains('\r') }) return false
        val text = runCatching {
            val parts = JSONArray(history.contentJson)
            if (parts.length() < 1) return false
            val first = parts.getJSONObject(0)
            if (first.optString("type") != "text") return false
            val retainedMedia = mutableListOf<Pair<String, String>>()
            for (i in 1 until parts.length()) {
                val part = parts.getJSONObject(i)
                when (part.optString("type")) {
                    "text" -> if (i != parts.length() - 1 || part.optString("text") != OMITTED) return false
                    "image_file", "video_file" -> retainedMedia += part.getString("type") to normalize(part.getString("path"))
                    else -> return false
                }
            }
            val expectedMedia = paths.mapIndexed { index, path ->
                (if (user.imageIsVideo.getOrNull(index) == true) "video_file" else "image_file") to normalize(path)
            }
            if (retainedMedia.isNotEmpty() && retainedMedia != expectedMedia) return false
            val value = first.getString("text")
            // Never choose one of two competing representations.
            if (history.content.isNotBlank() && history.content != value) return false
            normalize(value.trim())
        }.getOrNull() ?: return false
        val expected = normalize(visibleText.trim())
        fun same(actual: String, visible: String) = actual == visible || AgentRevisionRuntimeSuffix.matches(actual, visible)
        if (same(text, expected)) return true
        // Hydration appends exact known image paths, either before or after runtime suffixes.
        // Do not strip arbitrary marker-looking user text or guess paths by basename.
        val imagePaths = paths.filterIndexed { index, _ -> user.imageIsVideo.getOrNull(index) != true }
        if (imagePaths.isEmpty() || imagePaths.size != paths.size) return false
        val listing = normalize(imagePaths.joinToString("\n") { "[用户图片] $it" })
        val suffix = "\n\n$listing"
        fun withListing(visible: String) = same(text, visible) || same(text, visible + suffix) ||
            (text.endsWith(suffix) && same(text.removeSuffix(suffix), visible))
        if (withListing(expected)) return true
        // Older UI stored a files envelope while hydration retained request + media slots.
        // Only an exactly round-trippable envelope consisting of these known images may differ.
        val parsed = AgentFileReferencePromptCodec.parse(expected)
        val normalizedPaths = paths.map(normalize).toSet()
        if (parsed.references.isEmpty() || parsed.conversations.isNotEmpty() ||
            parsed.references.map { it.absolutePath }.toSet() != normalizedPaths ||
            AgentFileReferencePromptCodec.format(parsed.request, parsed.references) != expected) return false
        return withListing(parsed.request.trim())
    }
}
