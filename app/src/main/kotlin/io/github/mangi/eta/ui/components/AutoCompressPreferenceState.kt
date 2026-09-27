package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.mangi.eta.config.AutoCompressPreference

/** A read-only projection, never an independently writable or saveable copy of the setting. */
@Composable
internal fun rememberAutoCompressEnabled(preference: AutoCompressPreference): State<Boolean> {
    val enabled = remember(preference) { mutableStateOf(preference.enabled) }
    DisposableEffect(preference) {
        val observation = preference.observe { enabled.value = it }
        onDispose { observation.close() }
    }
    return enabled
}
