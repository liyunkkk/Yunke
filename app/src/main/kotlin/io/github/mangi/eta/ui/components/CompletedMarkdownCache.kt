package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * List-owned, bounded cache for completed default-GFM parses with link lookup.
 * The exact source is the key: custom parsers/configurations must not use it.
 * Stores no Context, layout, typography, coroutine or live parsing state.
 * AST nodes are shared read-only; the renderer's mutable link handler is NOT.
 * Character budget counts UTF-16 source units, not total heap bytes.
 */
internal class CompletedMarkdownCache(
    private val maxEntries: Int = 64,
    private val maxContentChars: Int = 262144,
) {
    init {
        require(maxEntries >= 0)
        require(maxContentChars >= 0)
    }

    private val entries = LinkedHashMap<String, CompletedMarkdownSnapshot>(16, 0.75f, true)
    private var retainedContentChars = 0

    val entryCount: Int get() = synchronized(entries) { entries.size }
    val contentChars: Int get() = synchronized(entries) { retainedContentChars }

    // One wrapper per mount. rememberCompletedMarkdownState retains it during
    // that mount; another row rendering the same text gets its own link table.
    fun get(content: String): MarkdownState? = synchronized(entries) {
        entries[content]?.newState()
    }

    fun put(content: String, success: State.Success, links: Map<String, String?>): Boolean =
        synchronized(entries) {
            if (maxEntries == 0 || maxContentChars == 0 || content.length > maxContentChars ||
                success.content != content || !success.linksLookedUp
            ) return@synchronized false

            val existing = entries[content]
            if (existing != null && existing.matches(success, links)) return@synchronized true
            if (existing != null) {
                entries.remove(content)
                retainedContentChars -= content.length
            }
            // Subtraction avoids overflow for large configured budgets.
            while (entries.size >= maxEntries || retainedContentChars > maxContentChars - content.length) {
                val oldest = entries.entries.iterator()
                retainedContentChars -= oldest.next().key.length
                oldest.remove()
            }
            entries[content] = CompletedMarkdownSnapshot(success, links)
            retainedContentChars += content.length
            true
        }
}

/** Immutable seed: never retains the mutable handler attached to source Success. */
private class CompletedMarkdownSnapshot(success: State.Success, sourceLinks: Map<String, String?>) {
    private val node = success.node
    private val content = success.content
    private val links: Map<String, String?> = Collections.unmodifiableMap(LinkedHashMap(sourceLinks))

    fun matches(success: State.Success, candidateLinks: Map<String, String?>): Boolean =
        node === success.node && content == success.content && links == candidateLinks

    fun newState(): MarkdownState {
        // The library annotator calls store() for inline links and autolinks.
        // Rejecting those writes crashes rendering; sharing them contaminates mounts.
        val handler = ReferenceLinkHandlerImpl().apply {
            links.forEach { (label, destination) -> store(label, destination) }
        }
        val completed = State.Success(node, content, true, handler)
        return object : MarkdownState {
            override val state: StateFlow<State> = MutableStateFlow<State>(completed).asStateFlow()
            override val links: StateFlow<Map<String, String?>> =
                MutableStateFlow(this@CompletedMarkdownSnapshot.links).asStateFlow()
            override suspend fun parse(): State = completed
        }
    }
}

internal val LocalCompletedMarkdownCache = staticCompositionLocalOf<CompletedMarkdownCache?> { null }

/** A miss stays on the async library path; a hit is Success before first composition. */
@Composable
internal fun rememberCompletedMarkdownState(content: String): MarkdownState {
    val cache = LocalCompletedMarkdownCache.current
    return key(cache, content) {
        // Remember misses too. A SideEffect cache insertion must not switch the
        // mounted parser/subtree. New content gets a new parser, never stale Success.
        val initialHit = remember { cache?.get(content) }
        initialHit ?: rememberMarkdownState(content, retainState = true)
    }
}

/** Reset the library's unkeyed collectAsState observer when its source changes. */
@Composable
internal fun CompletedMarkdownStateHost(markdownState: MarkdownState, content: @Composable () -> Unit) {
    key(markdownState) { content() }
}

/** Cache only successful applied work, not loading/errors or speculative composition. */
@Composable
internal fun CacheCompletedMarkdownSuccess(content: String, markdownState: MarkdownState, success: State.Success) {
    val cache = LocalCompletedMarkdownCache.current ?: return
    SideEffect {
        if (markdownState.state.value === success) {
            cache.put(content, success, markdownState.links.value)
        }
    }
}
