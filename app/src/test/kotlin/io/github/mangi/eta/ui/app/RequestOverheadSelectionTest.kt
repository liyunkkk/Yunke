package io.github.mangi.eta.ui.app

import org.junit.Assert.*
import org.junit.Test

class RequestOverheadSelectionTest {
    private val draft = RequestOverheadSelection.Binding("draft:d", "p", "m", "a", 1)

    @Test fun promotedDraftGetsANewRequestWithoutAcceptingItsOldCallback() {
        val selection = RequestOverheadSelection()
        val old = selection.begin(draft)
        val promoted = draft.copy(owner = "conversation:c")
        val replacement = selection.begin(promoted)
        assertFalse(selection.complete(old, promoted, 10_000))
        assertNull(selection.tokensFor(promoted))
        assertTrue(selection.complete(replacement, promoted, 20_000))
        assertEquals(20_000, selection.tokensFor(promoted))
        assertNull(selection.tokensFor(draft))
    }

    @Test fun unknownFailureKeepsOnlyTheSameBindingsSuccessfulEstimate() {
        val selection = RequestOverheadSelection()
        assertTrue(selection.complete(selection.begin(draft), draft, 12_345))
        assertFalse(selection.complete(selection.begin(draft), draft, null))
        assertEquals(12_345, selection.tokensFor(draft))
        val other = draft.copy(modelId = "other")
        assertFalse(selection.complete(selection.begin(other), other, null))
        assertNull(selection.tokensFor(other))
        assertEquals(12_345, selection.tokensFor(draft))
    }

    @Test fun allBindingDimensionsInvalidateEvenPositiveValues() {
        val changes = listOf(
            draft.copy(owner = "draft:new"), draft.copy(owner = "conversation:d"),
            draft.copy(providerId = "other"), draft.copy(modelId = "other"),
            draft.copy(assistantId = "other"), draft.copy(modelGeneration = 2),
        )
        changes.forEach { current ->
            val selection = RequestOverheadSelection()
            val request = selection.begin(draft)
            assertTrue(selection.complete(request, draft, 30_000))
            assertFalse(selection.complete(request, current, 99_999))
            assertNull(selection.tokensFor(current))
        }
    }

    @Test fun latestRequestWinsAndSuccessfulZeroIsDifferentFromUnknown() {
        val selection = RequestOverheadSelection()
        val first = selection.begin(draft)
        val latest = selection.begin(draft)
        assertTrue(selection.complete(latest, draft, 25_000))
        assertFalse(selection.complete(first, draft, 1))
        assertEquals(25_000, selection.tokensFor(draft))
        assertFalse(selection.complete(latest, draft, -1))
        assertTrue(selection.complete(selection.begin(draft), draft, 0))
        assertEquals(0, selection.tokensFor(draft))
    }

    @Test fun returningToAnOwnerDoesNotReviveAnOldInFlightCallback() {
        val selection = RequestOverheadSelection()
        val first = selection.begin(draft)
        selection.begin(draft.copy(owner = "conversation:other"))
        val returned = selection.begin(draft)
        assertFalse(selection.complete(first, draft, 10))
        assertTrue(selection.complete(returned, draft, 20))
    }
}
