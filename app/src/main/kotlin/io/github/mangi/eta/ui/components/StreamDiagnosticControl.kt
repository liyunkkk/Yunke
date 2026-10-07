package io.github.mangi.eta.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Read-only global override. No new permissions; adb/root owns writes and restore.
 * absent/0 = existing opt-in behaviour; 1 = all Eta observers/tracing OFF while ordinary
 * file logging remains unchanged. Unknown values fail closed. No polling or frame state.
 */
internal object StreamDiagnosticControl {
    const val KEY = "eta_stream_diagnostics_off"
    const val ASYNC_OUTPUT_KEY = "eta_stream_diagnostics_async_output"
    // Fail closed until startup reads the override, before any tracer is installed.
    @Volatile var allowed = false
        private set
    @Volatile var asyncOutputEnabled = true
        private set
    val changes = mutableIntStateOf(0)
    internal fun initialize(context: Context) {
        val resolver = context.applicationContext.contentResolver
        update(runCatching { Settings.Global.getString(resolver, KEY) }.getOrDefault("1"))
        updateAsyncOutput(runCatching { Settings.Global.getString(resolver, ASYNC_OUTPUT_KEY) }.getOrNull())
    }
    internal fun update(value: String?) {
        val next = value == null || value == "0"
        if (next != allowed) { allowed = next; changes.intValue++ }
    }
    internal fun updateAsyncOutput(value: String?) {
        val next = value != "0"
        if (next != asyncOutputEnabled) { asyncOutputEnabled = next; changes.intValue++ }
    }
}

@Composable
internal fun ObserveStreamDiagnosticControl(): Boolean {
    val resolver = LocalContext.current.applicationContext.contentResolver
    DisposableEffect(resolver) {
        fun refresh() {
            StreamDiagnosticControl.update(runCatching {
                Settings.Global.getString(resolver, StreamDiagnosticControl.KEY)
            }.getOrDefault("1"))
            StreamDiagnosticControl.updateAsyncOutput(runCatching {
                Settings.Global.getString(resolver, StreamDiagnosticControl.ASYNC_OUTPUT_KEY)
            }.getOrNull())
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(StreamDiagnosticControl.KEY), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(StreamDiagnosticControl.ASYNC_OUTPUT_KEY), false, observer)
        refresh()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    StreamDiagnosticControl.changes.intValue // changes only on a switch, never per frame
    return StreamDiagnosticControl.allowed
}
