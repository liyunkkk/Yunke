package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.NoOpImageTransformerImpl

/**
 * Reuse the library's stateless default without changing reference-link updates.
 * Reference annotations resolve a mutable handler during composition. Bracket
 * syntax therefore retains the original fresh static-local value, conservatively
 * including inline links, images, escaped brackets and code. Once seen, keep that
 * behavior even if a later correction removes the syntax from the current AST:
 * already frozen blocks may still contain it. No handler or local is frozen.
 */
internal class StreamingMarkdownImageTransformerPolicy {
    private val retained = NoOpImageTransformerImpl()
    private var bracketSyntaxSeen = false

    fun forContent(content: String): ImageTransformer {
        if (!bracketSyntaxSeen && '[' in content) bracketSyntaxSeen = true
        return if (bracketSyntaxSeen) NoOpImageTransformerImpl() else retained
    }
}

@Composable
internal fun rememberStreamingMarkdownImageTransformer(content: String): ImageTransformer {
    val policy = remember { StreamingMarkdownImageTransformerPolicy() }
    return policy.forContent(content)
}
