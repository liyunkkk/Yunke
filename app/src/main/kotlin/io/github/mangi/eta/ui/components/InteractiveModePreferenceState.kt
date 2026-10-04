package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.mangi.eta.config.InteractiveModePreference

@Composable
internal fun rememberInteractiveModeEnabled(preference: InteractiveModePreference): State<Boolean> {
    val enabled = remember(preference) { mutableStateOf(preference.enabled) }
    DisposableEffect(preference) {
        val observation = preference.observe { enabled.value = it }
        onDispose { observation.close() }
    }
    return enabled
}
