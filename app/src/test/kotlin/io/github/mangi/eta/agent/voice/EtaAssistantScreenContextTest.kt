package io.github.mangi.eta.agent.voice

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
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
