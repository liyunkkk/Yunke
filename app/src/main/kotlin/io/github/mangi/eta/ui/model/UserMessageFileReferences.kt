package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import java.net.URI

/** Display-only filtering. Keep the original prompt and persisted attachment references intact. */
internal fun UserMessageUi.visibleFileReferences(
    references: List<AgentFileReference>,
): List<AgentFileReference> {
    // Only entries actually represented by a media preview may hide a file card.
    val mediaPaths = images.indices.mapNotNull { index ->
        mediaFilePath(fullImageSourceAt(index))
    }.toSet()
    if (mediaPaths.isEmpty()) return references
    return references.filterNot { reference ->
        reference.kind == AgentFileReferenceKind.File &&
            mediaFilePath(reference.absolutePath)?.let { it in mediaPaths } == true
    }
}

private fun mediaFilePath(source: String): String? {
    val value = source.trim()
    if (value.startsWith("/")) return value
    if (!value.startsWith("file:", ignoreCase = true)) return null
    // Decode file URI escapes, but never deduplicate by filename or resolve filesystem aliases.
    return runCatching {
        val uri = URI(value)
        uri.path?.takeIf {
            uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && it.startsWith("/")
        }
    }.getOrNull()
}
