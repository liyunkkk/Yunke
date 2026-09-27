package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkProcessCardTest {
    @Test fun wholeCardHasNoVirtualExtension() {
        assertEquals(WorkProcessCardGeometry(0f, 0f, 48f),
            workProcessCardGeometry(WorkProcessCardPart.Whole, 48f, 14f, .5f))
    }

    @Test fun joinedEdgesAreOutsideTheVisibleSliceAtEveryDensity() {
        for (density in listOf(1f, 1.25f, 1.5f, 2.75f, 3.5f)) {
            val height = 32f * density
            val radius = 14f * density
            val stroke = .5f * density
            for (part in WorkProcessCardPart.entries) {
                val g = workProcessCardGeometry(part, height, radius, stroke)
                assertEquals(part.startsCard, g.topExtension == 0f)
                assertEquals(part.endsCard, g.bottomExtension == 0f)
                if (!part.startsCard) assertTrue(g.topExtension > radius)
                if (!part.endsCard) assertTrue(g.bottomExtension > radius)
                assertEquals(height, g.virtualHeight - g.topExtension - g.bottomExtension, .001f)
            }
        }
    }
}
