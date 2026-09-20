package io.github.mangi.eta.ui.haptics

import android.os.SystemClock

/** Suppress only navigation caused by a selection gesture; never consume selection pointer events. */
internal class SelectionLinkGuard(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var longPress = false
    private var blockedUntil = 0L

    fun onDown() {
        longPress = false
        blockedUntil = 0L
    }

    fun onLongPress() { longPress = true }

    fun onUp(elapsedMillis: Long, timeoutMillis: Long) {
        if (isSelectionLongPressRelease(elapsedMillis, timeoutMillis)) longPress = true
        onGestureFinished()
    }

    fun onGestureFinished() {
        if (longPress) {
            blockedUntil = now() + 300L
            longPress = false
        }
    }

    fun canOpenLink(): Boolean = !longPress && now() >= blockedUntil
}
