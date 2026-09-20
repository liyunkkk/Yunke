package io.github.mangi.eta.ui.haptics

import org.junit.Assert.*
import org.junit.Test

class SelectionLinkGuardTest {
    @Test fun shortTapStillNavigates() {
        val guard = SelectionLinkGuard { 1000L }
        guard.onDown(); guard.onUp(100, 500)
        assertTrue(guard.canOpenLink())
    }
    @Test fun longPressBlocksLinkDuringSelectionAndRelease() {
        val guard = SelectionLinkGuard { 1000L }
        guard.onDown(); guard.onLongPress()
        assertFalse(guard.canOpenLink())
        guard.onUp(800, 500); guard.onGestureFinished()
        assertFalse(guard.canOpenLink())
    }
    @Test fun releaseAtTimeoutBoundaryAlsoBlocksNavigation() {
        val guard = SelectionLinkGuard { 1000L }
        guard.onDown(); guard.onUp(500, 500)
        assertFalse(guard.canOpenLink())
    }
    @Test fun nextIntentionalTapIsNotSwallowed() {
        val guard = SelectionLinkGuard { 1000L }
        guard.onDown(); guard.onLongPress(); guard.onUp(800, 500)
        guard.onDown(); guard.onUp(100, 500)
        assertTrue(guard.canOpenLink())
    }
    @Test fun cancellationDoesNotPermanentlyDisableAccessibilityOrKeyboard() {
        var clock = 1000L
        val guard = SelectionLinkGuard { clock }
        guard.onDown(); guard.onLongPress(); guard.onGestureFinished()
        assertFalse(guard.canOpenLink())
        clock += 301
        assertTrue(guard.canOpenLink())
    }
    @Test fun plainScrollDoesNotDisableFutureNavigation() {
        val guard = SelectionLinkGuard { 1000L }
        guard.onDown(); guard.onGestureFinished()
        assertTrue(guard.canOpenLink())
    }
}
