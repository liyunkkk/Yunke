package io.github.mangi.eta.ui.screens.chat

import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ManageChatListItemTest {
    private val summary = ConversationSummaryUi(
        id = "chat",
        title = "Title",
        preview = "Old preview",
        timeLabel = "10:30",
        mode = ConversationModeUi.Chat,
    )

    @Test
    fun hiddenSummaryChangesDoNotInvalidateListContent() {
        val refreshed = summary.copy(
            preview = "New background-run preview",
            updatedAtMillis = 123L,
            createdAtMillis = 100L,
            folderId = "folder",
            mode = ConversationModeUi.Automation,
            isToday = true,
        )
        assertNotEquals(summary, refreshed)
        assertEquals(listOf(summary.manageChatListItem()), listOf(refreshed.manageChatListItem()))
    }

    @Test
    fun incomingActivityOrderDoesNotChangeProjectedLookup() {
        val other = summary.copy(id = "other", title = "Other")
        val original = listOf(summary, other).associate { it.id to it.manageChatListItem() }
        val refreshed = listOf(other, summary.copy(preview = "New preview"))
            .associate { it.id to it.manageChatListItem() }
        assertEquals(original, refreshed)
    }

    @Test
    fun everyDisplayedFieldStillInvalidatesContent() {
        val changed = listOf(
            summary.copy(id = "other"),
            summary.copy(title = "Renamed"),
            summary.copy(timeLabel = "Yesterday"),
            summary.copy(isPinned = true),
            summary.copy(isActiveRun = true),
            summary.copy(hasCompletionMarker = true),
        )
        changed.forEach {
            assertNotEquals(summary.manageChatListItem(), it.manageChatListItem())
        }
    }

    @Test
    fun blankTitleStillUsesLivePreview() {
        val untitled = summary.copy(title = "  ")
        assertEquals("Old preview", untitled.manageChatListItem().title)
        assertEquals("Updated", untitled.copy(preview = "Updated").manageChatListItem().title)
        assertNotEquals(
            untitled.manageChatListItem(),
            untitled.copy(preview = "Updated").manageChatListItem(),
        )
    }
}
