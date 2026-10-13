package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray

/** One successful parent response, independent of its screen route or selected conversation. */
internal object ConversationCompletionNotice {
    fun confirmed(result: AgentRuntimeWire.RunResult, wasStopped: Boolean = false): Boolean =
        !wasStopped && result.ok && result.error.isNullOrBlank()

    fun text(title: String): String =
        "${title.replace(Regex("\\s+"), " ").trim().ifBlank { "未命名会话" }} · 已完成"

    private const val PREFS = "conversation_completion_notices"
    private const val RUNS = "shown_runs"
    private const val MAX_RUNS = 1024
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var shownRuns: LinkedHashSet<String>? = null

    @Synchronized
    fun show(context: Context, runId: String, title: String, result: AgentRuntimeWire.RunResult,
        wasStopped: Boolean = false) {
        if (runId.isBlank() || !confirmed(result, wasStopped)) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = shownRuns ?: linkedSetOf<String>().also { restored ->
            val saved = runCatching { JSONArray(prefs.getString(RUNS, "[]")) }.getOrElse { JSONArray() }
            for (index in 0 until saved.length()) {
                saved.optString(index).takeIf { it.isNotBlank() }?.let(restored::add)
            }
            shownRuns = restored
        }
        if (runId in runs) return
        val message = text(title)
        if (!mainHandler.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }) return
        runs.add(runId)
        while (runs.size > MAX_RUNS) runs.remove(runs.first())
        prefs.edit().putString(RUNS, JSONArray(runs.toList()).toString()).apply()
    }
}
