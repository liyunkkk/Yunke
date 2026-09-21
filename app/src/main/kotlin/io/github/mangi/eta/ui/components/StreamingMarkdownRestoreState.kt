package io.github.mangi.eta.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** 恢复动画的边界由已排版的内容决定，旧页面的布局回调不能解除新一轮暂停。 */
internal class StreamingMarkdownRestoreState {
    var generation by mutableIntStateOf(0)
        private set
    private var baseline by mutableStateOf<String?>(null)
    private var foreground by mutableStateOf(false)
    private var entered = false

    fun animationsAllowed(paused: Boolean): Boolean = foreground && baseline == null && !paused

    /** Only an actual re-entry restores history. The first live delta is not history. */
    fun begin(content: String, live: Boolean = false): Boolean {
        val firstLiveEntry = !entered && live
        entered = true
        generation += 1
        baseline = if (firstLiveEntry) null else content
        foreground = true
        return firstLiveEntry
    }

    fun pause() {
        generation += 1
        baseline = null
        foreground = false
    }

    fun completeLayout(generation: Int, renderedContent: String, currentContent: String): Boolean {
        val pending = baseline ?: return false
        if (generation != this.generation) return false
        val caughtUp = renderedContent == currentContent ||
            (renderedContent.startsWith(pending) && currentContent.startsWith(renderedContent))
        if (!caughtUp) return false
        baseline = null
        return true
    }
}

internal fun isStreamingMarkdownTargetComplete(
    content: String,
    isStreaming: Boolean,
    snapshotContent: String?,
    snapshotComplete: Boolean,
): Boolean = !isStreaming && snapshotComplete && snapshotContent == content
