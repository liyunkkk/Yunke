package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Capture only on entry to the existing freeze branch. Leaving it discards the
 * slot, so a later freeze captures the current input rather than an earlier tail.
 * Components and current Markdown/typography locals must not be pinned here.
 */
@Composable
internal fun <T> rememberFrozenMarkdownInput(value: T, freeze: Boolean): T =
    if (freeze) {
        remember { value }
    } else {
        value
    }
