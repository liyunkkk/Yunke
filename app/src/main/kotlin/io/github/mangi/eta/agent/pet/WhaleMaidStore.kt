package io.github.mangi.eta.agent.pet

import android.content.Context
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import org.json.JSONArray
import org.json.JSONObject

internal data class WhaleMaidMemory(
    val mood: String,
    val speech: String,
    val tokensUsed: Int,
    val atMillis: Long,
    val eventType: String,
)

internal data class WhaleMaidTask(
    val title: String,
    val atMillis: Long,
)

internal data class WhaleMaidSnapshot(
    val enabled: Boolean,
    val workSpeechEnabled: Boolean,
    val satiety: Int,
    val scale: Float,
    val x: Int,
    val y: Int,
    val mood: String,
    val speech: String,
    val speechVisible: Boolean,
    val thinking: Boolean,
    val memories: List<WhaleMaidMemory>,
    val recentTasks: List<WhaleMaidTask>,
)

internal object WhaleMaidStore {
    private const val PREFS = "whale_maid"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WORK_SPEECH = "work_speech"
    private const val KEY_SATIETY = "satiety"
    private const val KEY_SCALE = "scale"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_MOOD = "mood"
    private const val KEY_SPEECH = "speech"
    private const val KEY_SPEECH_VISIBLE = "speech_visible"
    private const val KEY_THINKING = "thinking"
    private const val KEY_MEMORIES = "memories"
    private const val KEY_TASKS = "tasks"
    private const val KEY_MODEL_CUSTOM = "model_custom"
    private const val KEY_MODEL_PROVIDER = "model_provider"
    private const val KEY_MODEL_ID = "model_id"
    private const val TASK_WINDOW_MS = 5L * 60L * 60L * 1000L

    private val listeners = mutableListOf<(WhaleMaidSnapshot) -> Unit>()

    fun snapshot(context: Context): WhaleMaidSnapshot = read(context.applicationContext)

    fun addListener(listener: (WhaleMaidSnapshot) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: (WhaleMaidSnapshot) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun workSpeechEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WORK_SPEECH, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            prefs(context).edit().putBoolean(KEY_THINKING, false).apply()
        }
        publish(context)
    }

    fun setWorkSpeechEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WORK_SPEECH, enabled).apply()
        publish(context)
    }

    fun modelSelection(context: Context): ModelFeatureSelection {
        val preferences = prefs(context)
        return ModelFeatureSelection(
            custom = preferences.getBoolean(KEY_MODEL_CUSTOM, false),
            providerId = preferences.getString(KEY_MODEL_PROVIDER, "").orEmpty(),
            modelId = preferences.getString(KEY_MODEL_ID, "").orEmpty(),
        )
    }

    fun setModelSelection(context: Context, selection: ModelFeatureSelection) {
        prefs(context).edit()
            .putBoolean(KEY_MODEL_CUSTOM, selection.custom)
            .putString(KEY_MODEL_PROVIDER, selection.providerId)
            .putString(KEY_MODEL_ID, selection.modelId)
            .apply()
        publish(context)
    }

    fun setScale(context: Context, scale: Float) {
        val clamped = ((scale.coerceIn(0.5f, 1.8f) * 20f).toInt() / 20f)
        prefs(context).edit().putFloat(KEY_SCALE, clamped).apply()
        publish(context)
    }

    fun setPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
        publish(context)
    }

    fun addSatiety(context: Context, tokens: Int) {
        val next = (satiety(context) + tokens.coerceAtLeast(0)).coerceAtMost(WHALE_MAID_MAX_SATIETY)
        prefs(context).edit().putInt(KEY_SATIETY, next).apply()
        publish(context)
    }

    fun consume(context: Context, tokens: Int) {
        val next = (satiety(context) - tokens.coerceAtLeast(0)).coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_SATIETY, next).apply()
        publish(context)
    }

    fun satiety(context: Context): Int = prefs(context).getInt(KEY_SATIETY, 5_000).coerceIn(0, WHALE_MAID_MAX_SATIETY)

    fun beginThinking(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_THINKING, true)
            .putString(KEY_MOOD, "thinking")
            .putBoolean(KEY_SPEECH_VISIBLE, false)
            .apply()
        publish(context)
    }

    fun present(context: Context, reaction: WhaleMaidReaction) {
        prefs(context).edit()
            .putBoolean(KEY_THINKING, false)
            .putString(KEY_MOOD, reaction.mood)
            .putString(KEY_SPEECH, reaction.speech)
            .putBoolean(KEY_SPEECH_VISIBLE, true)
            .apply()
        publish(context)
    }

    fun finishPose(context: Context, mood: String) {
        val preferences = prefs(context)
        if (preferences.getBoolean(KEY_THINKING, false)) return
        if (mood == "idle" || preferences.getString(KEY_MOOD, "idle") != mood) return
        preferences.edit().putString(KEY_MOOD, "idle").apply()
        publish(context)
    }

    fun dismissSpeech(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_SPEECH_VISIBLE, false)
            .putString(KEY_SPEECH, "")
            .putString(KEY_MOOD, "idle")
            .putBoolean(KEY_THINKING, false)
            .apply()
        publish(context)
    }

    fun rememberTask(context: Context, title: String) {
        val trimmed = title.trim().ifBlank { return }
        val now = System.currentTimeMillis()
        val kept = recentTasks(context).filter { now - it.atMillis <= TASK_WINDOW_MS && it.title != trimmed }
        val next = (listOf(WhaleMaidTask(trimmed, now)) + kept).take(8)
        prefs(context).edit().putString(KEY_TASKS, tasksJson(next)).apply()
    }

    fun recentTaskTitles(context: Context): List<String> {
        val now = System.currentTimeMillis()
        return recentTasks(context).filter { now - it.atMillis <= TASK_WINDOW_MS }.map { it.title }
    }

    fun recordMemory(context: Context, memory: WhaleMaidMemory) {
        val next = (listOf(memory) + memories(context)).take(20)
        prefs(context).edit().putString(KEY_MEMORIES, memoriesJson(next)).apply()
        publish(context)
    }

    fun clearMemories(context: Context) {
        prefs(context).edit().putString(KEY_MEMORIES, "[]").apply()
        publish(context)
    }

    fun memorySpeeches(context: Context): List<String> = memories(context).map { it.speech }

    private fun recentTasks(context: Context): List<WhaleMaidTask> =
        decodeTasks(prefs(context).getString(KEY_TASKS, "[]").orEmpty())

    private fun memories(context: Context): List<WhaleMaidMemory> =
        decodeMemories(prefs(context).getString(KEY_MEMORIES, "[]").orEmpty())

    private fun read(context: Context): WhaleMaidSnapshot {
        val preferences = prefs(context)
        return WhaleMaidSnapshot(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            workSpeechEnabled = preferences.getBoolean(KEY_WORK_SPEECH, true),
            satiety = preferences.getInt(KEY_SATIETY, 5_000).coerceIn(0, WHALE_MAID_MAX_SATIETY),
            scale = preferences.getFloat(KEY_SCALE, 1f).coerceIn(0.5f, 1.8f),
            x = preferences.getInt(KEY_X, -1),
            y = preferences.getInt(KEY_Y, -1),
            mood = preferences.getString(KEY_MOOD, "idle").orEmpty().ifBlank { "idle" },
            speech = preferences.getString(KEY_SPEECH, "").orEmpty(),
            speechVisible = preferences.getBoolean(KEY_SPEECH_VISIBLE, false),
            thinking = preferences.getBoolean(KEY_THINKING, false),
            memories = memories(context),
            recentTasks = recentTasks(context),
        )
    }

    private fun publish(context: Context) {
        val snapshot = read(context.applicationContext)
        val current = synchronized(listeners) { listeners.toList() }
        current.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun memoriesJson(items: List<WhaleMaidMemory>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("mood", item.mood)
                .put("speech", item.speech)
                .put("tokens", item.tokensUsed)
                .put("at", item.atMillis)
                .put("event", item.eventType))
        }
        return array.toString()
    }

    private fun tasksJson(items: List<WhaleMaidTask>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("title", item.title).put("at", item.atMillis))
        }
        return array.toString()
    }

    private fun decodeMemories(raw: String): List<WhaleMaidMemory> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val speech = item.optString("speech")
                if (speech.isBlank()) continue
                add(WhaleMaidMemory(
                    mood = item.optString("mood").ifBlank { "happy" },
                    speech = speech,
                    tokensUsed = item.optInt("tokens"),
                    atMillis = item.optLong("at"),
                    eventType = item.optString("event"),
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeTasks(raw: String): List<WhaleMaidTask> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title")
                if (title.isBlank()) continue
                add(WhaleMaidTask(title, item.optLong("at")))
            }
        }
    }.getOrDefault(emptyList())
}
