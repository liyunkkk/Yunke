package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiActiveTest {
    @Test
    fun onlySettledCoveredRouteParksUiClocks() {
        assertTrue(isChatSettledCovered(routeCovered = true, transitionActive = false))
        assertFalse(isChatSettledCovered(routeCovered = true, transitionActive = true))
        assertFalse(isChatSettledCovered(routeCovered = false, transitionActive = true))
        assertFalse(isChatSettledCovered(routeCovered = false, transitionActive = false))
    }
}
