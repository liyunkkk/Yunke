package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPerformanceDiagnosticsTest {
    @Test fun unaccountedIsTotalMinusNonOverlappingParts() {
        assertEquals(10L, frameUnaccountedNs(
            total = 100, unknown = 40, input = 5, animation = 5,
            layout = 10, draw = 10, sync = 10, command = 5, swap = 5,
        ))
    }

    @Test fun gpuIsNotSubtractedFromTheTotal() {
        assertEquals(0L, frameUnaccountedNs(
            total = 80, unknown = 80, input = 0, animation = 0,
            layout = 0, draw = 0, sync = 0, command = 0, swap = 0,
        ))
    }

    @Test fun overlapIsNegativeWhenPartsExceedTotal() {
        assertEquals(-3L, frameUnaccountedNs(
            total = 7, unknown = 4, input = 3, animation = 3,
            layout = 0, draw = 0, sync = 0, command = 0, swap = 0,
        ))
    }

    @Test fun looperLineKeepsOnlyHandlerAndCallbackClass() {
        assertEquals(
            "android.view.Choreographer\$FrameHandler/android.view.Choreographer\$FrameDisplayEventReceiver",
            toggleProbeMessageName(
                ">>>>> Dispatching to Handler (android.view.Choreographer\$FrameHandler) {9f1c2d3} " +
                    "android.view.Choreographer\$FrameDisplayEventReceiver@4a5b6c7: 0",
            ),
        )
    }

    @Test fun looperLineWithoutCallbackStaysBounded() {
        val name = toggleProbeMessageName(
            ">>>>> Dispatching to Handler (android.app.ActivityThread\$H) {1a2b3c} null: 159",
        )
        assertEquals("android.app.ActivityThread\$H/null", name)
        assertTrue(name.length <= 160)
    }

    @Test fun toggleProbeClosesAfterFixedFrameWindow() {
        val probe = ToggleProbe(kind = "tool", expanded = true, startNs = 0L)
        repeat(TOGGLE_PROBE_FRAMES - 1) { assertFalse(probe.addFrame("f")) }
        assertTrue(probe.addFrame("f"))
        assertFalse(probe.expired(TOGGLE_PROBE_MAX_NS - 1))
        assertTrue(probe.expired(TOGGLE_PROBE_MAX_NS))
    }

    @Test fun customRunnableToStringCannotLeakFieldValues() {
        val name = toggleProbeMessageName(
            ">>>>> Dispatching to Handler (android.os.Handler) {1a2b} Job(text=secret message, id=42): 0",
        )
        assertEquals("android.os.Handler/Job", name)
    }
}
