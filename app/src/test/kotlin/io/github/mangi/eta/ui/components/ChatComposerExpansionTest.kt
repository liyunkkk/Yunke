package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerExpansionTest {
    private fun expanded(
        inputFocused: Boolean = false,
        hasPendingImages: Boolean = false,
        hasPendingFileReferences: Boolean = false,
        hasPendingMentions: Boolean = false,
        isEditingMessage: Boolean = false,
        isStreaming: Boolean = false,
        isPaused: Boolean = false,
        canContinueDisconnected: Boolean = false,
        isCompressingContext: Boolean = false,
    ): Boolean = resolveComposerExpanded(
        inputFocused = inputFocused,
        hasPendingImages = hasPendingImages,
        hasPendingFileReferences = hasPendingFileReferences,
        hasPendingMentions = hasPendingMentions,
        isEditingMessage = isEditingMessage,
        isStreaming = isStreaming,
        isPaused = isPaused,
        canContinueDisconnected = canContinueDisconnected,
        isCompressingContext = isCompressingContext,
    )

    @Test
    fun emptyUnfocusedComposerStaysCollapsed() {
        assertFalse(expanded())
    }

    @Test
    fun focusingAnEmptyComposerExpandsIt() {
        assertTrue(expanded(inputFocused = true))
    }

    @Test
    fun unfocusedComposerCollapsesEvenThoughDraftTextMayStillExist() {
        // 草稿文字不参与展开判定：失焦后即使还有文字也收回单行胶囊。
        assertFalse(expanded(inputFocused = false))
    }

    @Test
    fun pendingAttachmentsForceExpansionWithoutFocus() {
        assertTrue(expanded(hasPendingImages = true))
        assertTrue(expanded(hasPendingFileReferences = true))
        assertTrue(expanded(hasPendingMentions = true))
    }

    @Test
    fun editingAndRunningStatesKeepTheirActionEntriesVisible() {
        assertTrue(expanded(isEditingMessage = true))
        assertTrue(expanded(isStreaming = true))
        assertTrue(expanded(isPaused = true))
        assertTrue(expanded(canContinueDisconnected = true))
        assertTrue(expanded(isCompressingContext = true))
    }

    @Test
    fun focusExpandsRegardlessOfBusinessState() {
        assertTrue(expanded(inputFocused = true, isStreaming = true))
        assertTrue(expanded(inputFocused = true, isEditingMessage = true))
    }
}
