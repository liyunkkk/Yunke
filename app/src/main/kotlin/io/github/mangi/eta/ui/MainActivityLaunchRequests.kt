package io.github.mangi.eta.ui

import android.content.Intent

internal data class ArchivedConversationLaunchRequest(
    val source: String,
    val conversationKey: String,
)

internal object MainActivityLaunchRequests {
    fun parseArchivedConversationRequest(intent: Intent?): ArchivedConversationLaunchRequest? {
        if (intent?.action != MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION) return null
        val source = intent.getStringExtra(MainActivity.EXTRA_HANDOFF_SOURCE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val key = intent.getStringExtra(MainActivity.EXTRA_CONVERSATION_KEY)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return ArchivedConversationLaunchRequest(
            source = source,
            conversationKey = key,
        )
    }

    fun parseScheduledTaskDetailId(intent: Intent?): String? {
        if (intent?.action != MainActivity.ACTION_OPEN_SCHEDULED_TASK_DETAIL) return null
        return intent.getStringExtra(MainActivity.EXTRA_SCHEDULED_TASK_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }
}
