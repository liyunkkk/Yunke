package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressingIndicatorOwnershipTest {
    @Test fun otherConversationsJobDoesNotKeepIndicator() {
        assertFalse(keepsCompressingIndicator("conv-b", jobActive = true, jobConversationId = "conv-a",
            hasPending = false, pendingConversationId = null))
    }

    @Test fun ownJobKeepsIndicator() {
        assertTrue(keepsCompressingIndicator("conv-a", jobActive = true, jobConversationId = "conv-a",
            hasPending = false, pendingConversationId = null))
    }

    @Test fun finishedJobDoesNotKeepIndicator() {
        assertFalse(keepsCompressingIndicator("conv-a", jobActive = false, jobConversationId = "conv-a",
            hasPending = false, pendingConversationId = null))
    }

    @Test fun unknownOwnerStaysConservative() {
        assertTrue(keepsCompressingIndicator("conv-b", jobActive = true, jobConversationId = null,
            hasPending = false, pendingConversationId = null))
        assertTrue(keepsCompressingIndicator(null, jobActive = true, jobConversationId = "conv-a",
            hasPending = false, pendingConversationId = null))
    }

    @Test fun pendingManualCompressOnlyForItsConversation() {
        assertTrue(keepsCompressingIndicator("conv-a", jobActive = false, jobConversationId = null,
            hasPending = true, pendingConversationId = "conv-a"))
        assertFalse(keepsCompressingIndicator("conv-b", jobActive = false, jobConversationId = null,
            hasPending = true, pendingConversationId = "conv-a"))
        assertTrue(keepsCompressingIndicator("conv-b", jobActive = false, jobConversationId = null,
            hasPending = true, pendingConversationId = null))
    }
}
