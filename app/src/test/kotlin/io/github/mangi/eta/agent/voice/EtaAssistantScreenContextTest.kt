package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaAssistantScreenContextTest {
    @Test
    fun freshVisibleEntryCoalescesDuplicateShowsUntilDismissedOrExpired() {
        assertTrue(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 3_500L,
            ),
        )
        assertFalse(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = true,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_500L,
            ),
        )
        assertFalse(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = true,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_500L,
            ),
        )
        assertFalse(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_000L + EtaAssistantOverlayService.FRESH_ENTRY_COALESCE_WINDOW_MS + 1L,
            ),
        )
    }
}
