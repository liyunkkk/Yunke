package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomFollowObservationTest {
    @Test fun branchResultMatchesOriginalForAllInputsAndOnlyScansWhenNeeded() {
        for (streaming in listOf(false, true)) for (anchored in listOf(false, true)) {
            for (user in listOf(false, true)) for (pending in listOf(false, true)) {
                var calls = 0
                val actual = bottomFollowSettlingState(streaming, anchored, user) { calls++; pending }
                val expected = when {
                    !anchored || user -> BottomFollowSettlingState.Disabled
                    streaming || pending -> BottomFollowSettlingState.Active
                    else -> BottomFollowSettlingState.Draining
                }
                assertEquals(expected, actual)
                assertEquals(if (anchored && !user && !streaming) 1 else 0, calls)
            }
        }
    }

    @Test fun disabledLayoutCannotPublishStaleScrollOrNavigationRequest() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(
            enabled = false, bottomItemIndex = 99, sentinelBottom = 10000,
            viewportEnd = 500, lastVisibleIndex = 3, viewportSizePx = 500, canScrollForward = true,
        ))
        assertEquals(BottomFollowDecision(scrollByPx = 50), resolveBottomFollowDecision(
            enabled = true, bottomItemIndex = 99, sentinelBottom = 550,
            viewportEnd = 500, lastVisibleIndex = 99, viewportSizePx = 500, canScrollForward = true,
        ))
    }
}
