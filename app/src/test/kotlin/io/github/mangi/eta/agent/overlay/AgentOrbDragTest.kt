package io.github.mangi.eta.agent.overlay

import org.junit.Assert.*
import org.junit.Test

class AgentOrbDragTest {
    @Test fun jitterWithinTouchSlopRemainsOneClick() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(7, 100f, 200f)
        assertNull(gesture.move(7, 104f, 203f))
        assertNull(gesture.move(7, 108f, 200f))
        assertTrue(gesture.finish(7))
        assertFalse(gesture.finish(7))
    }

    @Test fun dragUsesTotalRawDisplacementIncludingFractions() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 100f, 200f)
        assertEquals(AgentOrbPosition(10.25f, -4.5f), gesture.move(1, 110.25f, 195.5f))
        assertEquals(AgentOrbPosition(11.5f, -5.25f), gesture.move(1, 111.5f, 194.75f))
        assertFalse(gesture.finish(1))
    }

    @Test fun diagonalDistanceCrossesSlop() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 0f, 0f)
        assertEquals(AgentOrbPosition(6f, 6f), gesture.move(1, 6f, 6f))
        assertFalse(gesture.finish(1))
    }

    @Test fun returningToStartAfterDraggingDoesNotBecomeAClick() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 100f, 200f)
        gesture.move(1, 120f, 200f)
        assertEquals(AgentOrbPosition(0f, 0f), gesture.move(1, 100f, 200f))
        assertFalse(gesture.finish(1))
    }

    @Test fun cancelledAndWrongPointerGesturesNeverClickOrMove() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 100f, 200f)
        gesture.cancel()
        assertNull(gesture.move(1, 200f, 300f))
        assertFalse(gesture.finish(1))
        gesture.begin(1, 100f, 200f)
        assertNull(gesture.move(2, 200f, 300f))
        assertFalse(gesture.finish(1))
        gesture.begin(1, 100f, 200f)
        assertFalse(gesture.finish(2))
    }

    @Test fun newDownResetsPriorDragAndInvalidCoordinatesCancel() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 100f, 200f)
        gesture.move(1, 200f, 300f)
        gesture.begin(2, 20f, 30f)
        assertTrue(gesture.finish(2))
        gesture.begin(1, Float.NaN, 0f)
        assertFalse(gesture.finish(1))
        gesture.begin(1, 0f, 0f)
        assertNull(gesture.move(1, Float.POSITIVE_INFINITY, 0f))
        assertFalse(gesture.finish(1))
    }

    @Test fun keyboardRequiresMatchedPressAndReleaseAndIgnoresRepeats() {
        val key = AgentOrbActivationKey()
        assertFalse(key.up(13))
        key.down(13)
        key.down(13)
        assertTrue(key.up(13))
        assertFalse(key.up(13))
    }

    @Test fun cancelledOrMismatchedKeyReleaseDoesNotClick() {
        val key = AgentOrbActivationKey()
        key.down(13)
        key.cancel()
        assertFalse(key.up(13))
        key.down(13)
        assertFalse(key.up(32))
        assertFalse(key.up(13))
    }

    @Test fun finalReleaseMovementCanCrossSlopWithoutIntermediateMoves() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 100f, 200f)
        assertEquals(AgentOrbPosition(10f, 0f), gesture.move(1, 110f, 200f))
        assertFalse(gesture.finish(1))
    }

    @Test fun multiTouchCancelKeepsRemainingStreamInertUntilNewDown() {
        val gesture = AgentOrbDragGesture(8f)
        gesture.begin(1, 100f, 200f)
        gesture.move(1, 120f, 200f)
        gesture.cancel()
        assertNull(gesture.move(1, 160f, 250f))
        assertFalse(gesture.finish(1))
        gesture.begin(1, 160f, 250f)
        assertTrue(gesture.finish(1))
    }

    private val bounds = AgentOrbBounds(12, 24, 1012, 1824)

    @Test fun orbStaysInsideAllSafeEdgesAndKeepsSubpixelPrecision() {
        assertEquals(AgentOrbPosition(12f, 24f), AgentOrbPlacement.clamp(
            AgentOrbPosition(-100f, -200f), bounds, 56, 56))
        assertEquals(AgentOrbPosition(956f, 1768f), AgentOrbPlacement.clamp(
            AgentOrbPosition(2000f, 3000f), bounds, 56, 56))
        assertEquals(AgentOrbPosition(100.25f, 200.75f), AgentOrbPlacement.clamp(
            AgentOrbPosition(100.25f, 200.75f), bounds, 56, 56))
    }

    @Test fun initialPlacementIsNearPhysicalRightEdge() {
        assertEquals(AgentOrbPosition(948f, 1104f), AgentOrbPlacement.initial(bounds, 56, 56, 8))
    }

    @Test fun bubbleFollowsLeftOnRightSideAndFlipsOnLeftSide() {
        assertEquals(AgentOrbPosition(712f, 400f), AgentOrbPlacement.bubble(
            AgentOrbPosition(900f, 400f), 56, 56, 180, 100, bounds, 8))
        assertEquals(AgentOrbPosition(76f, 400f), AgentOrbPlacement.bubble(
            AgentOrbPosition(12f, 400f), 56, 56, 180, 100, bounds, 8))
    }

    @Test fun narrowDisplayPlacesBubbleBelowOrAboveWithoutCoveringOrb() {
        val narrow = AgentOrbBounds(0, 0, 250, 600)
        assertEquals(AgentOrbPosition(70f, 164f), AgentOrbPlacement.bubble(
            AgentOrbPosition(90f, 100f), 56, 56, 180, 100, narrow, 8))
        assertEquals(AgentOrbPosition(70f, 392f), AgentOrbPlacement.bubble(
            AgentOrbPosition(90f, 500f), 56, 56, 180, 100, narrow, 8))
    }

    @Test fun tallerBubbleAndChangedBoundsAreClamped() {
        assertEquals(AgentOrbPosition(712f, 1524f), AgentOrbPlacement.bubble(
            AgentOrbPosition(900f, 1768f), 56, 56, 180, 300, bounds, 8))
        val smaller = AgentOrbBounds(10, 20, 410, 520)
        assertEquals(AgentOrbPosition(354f, 464f), AgentOrbPlacement.clamp(
            AgentOrbPosition(900f, 1768f), smaller, 56, 56))
        assertEquals(AgentOrbPosition(10f, 20f), AgentOrbPlacement.clamp(
            AgentOrbPosition(900f, 1768f), AgentOrbBounds(10, 20, 40, 45), 56, 56))
    }
}
