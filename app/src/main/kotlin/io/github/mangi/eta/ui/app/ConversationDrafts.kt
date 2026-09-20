package io.github.mangi.eta.ui.app

import android.content.SharedPreferences
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Owned by the app state, not a destination. Typing never updates the message list. */
internal class ConversationDrafts(
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
) {
    private val fields = mutableMapOf<String, TextFieldState>()
    private val observers = mutableMapOf<String, Job>()
    private fun key(conversationId: String?) = conversationId?.let { "conversation:$it" } ?: "new-conversation"

    fun field(conversationId: String?): TextFieldState {
        val key = key(conversationId)
        return fields.getOrPut(key) {
            TextFieldState(preferences.getString(key, "").orEmpty()).also { field ->
                observers[key] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    snapshotFlow { field.text.toString() }.collect { text ->
                        preferences.edit().apply {
                            if (text.isEmpty()) remove(key) else putString(key, text)
                        }.apply()
                    }
                }
            }
        }
    }

    fun replace(conversationId: String?, text: String) {
        val field = field(conversationId)
        if (field.text.toString() != text) field.setTextAndPlaceCursorAtEnd(text)
        // Persist explicit navigation/send changes without waiting for snapshot observation.
        preferences.edit().apply {
            if (text.isEmpty()) remove(key(conversationId)) else putString(key(conversationId), text)
        }.apply()
    }

    fun promote(conversationId: String) {
        replace(conversationId, field(null).text.toString())
        replace(null, "")
    }

    fun remove(conversationId: String?) {
        val key = key(conversationId)
        observers.remove(key)?.cancel()
        fields.remove(key)
        preferences.edit().remove(key).apply()
    }

    fun clear() {
        observers.values.forEach { it.cancel() }
        observers.clear()
        fields.clear()
        preferences.edit().clear().apply()
    }
}
