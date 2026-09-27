package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelOwnerPopupTest {
    private sealed interface Owner {
        data class Conversation(val id: String) : Owner
        data class Draft(val token: String) : Owner
    }

    @Test fun oldCollaborationToggleCannotWriteNewConversationAfterLoadingCompletes() {
        val a = Owner.Conversation("A")
        val b = Owner.Conversation("B")
        val saved = mutableMapOf<Owner, Boolean>()
        var current = OwnerBoundPopup<Owner>(a)
        val oldPopup = current
        val oldTicket = oldPopup.open()
        val oldToggle = { value: Boolean ->
            oldPopup.dispatch(oldTicket, current) { saved[a] = value }
        }
        current = OwnerBoundPopup(b) // B's asynchronous editor replaces A's
        assertFalse(oldToggle(false))
        assertTrue(saved.isEmpty())
        val newTicket = current.open()
        assertTrue(current.dispatch(newTicket, current) { saved[b] = false })
        assertEquals(mapOf<Owner, Boolean>(b to false), saved)
        current = OwnerBoundPopup(a)
        assertFalse(oldToggle(true)) // A -> B -> A cannot revive the old callback
        assertEquals(mapOf<Owner, Boolean>(b to false), saved)
    }

    @Test fun oldModelSelectionCannotChangeNewOwnerIncludingDifferentDrafts() {
        val first = Owner.Draft("first-draft-token")
        val second = Owner.Draft("second-draft-token")
        val selected = mutableMapOf<Owner, String>()
        var current = OwnerBoundPopup<Owner>(first)
        val firstPopup = current
        val firstTicket = firstPopup.open()
        val oldChoose = { model: String ->
            firstPopup.dispatch(firstTicket, current) { selected[current.owner] = model }
        }
        current = OwnerBoundPopup(second)
        assertFalse(oldChoose("old-model"))
        assertTrue(selected.isEmpty())
        val secondTicket = current.open()
        assertTrue(current.dispatch(secondTicket, current) { selected[current.owner] = "new-model" })
        assertEquals(mapOf<Owner, String>(second to "new-model"), selected)
        assertFalse(oldChoose("old-model-again"))
        assertEquals("new-model", selected[second])
    }

    @Test fun reopeningSameOwnerInvalidatesPriorSelectionAndDismissedEntry() {
        val popup = OwnerBoundPopup(Owner.Conversation("A"))
        val first = popup.open()
        val second = popup.open()
        var selected = ""
        assertFalse(popup.dispatch(first, popup) { selected = "stale" })
        assertTrue(popup.dispatch(second, popup) { selected = "live" })
        assertEquals("live", selected)
        popup.dismiss()
        assertFalse(popup.dispatch(second, popup) { selected = "dismissed" })
        assertEquals("live", selected)
    }
}
