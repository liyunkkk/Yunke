package io.github.mangi.eta.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorScrollIsolationTest {
    @Test
    fun editorKeepsOwnershipUntilNextPageGestureIncludingFling() = runBlocking {
        val isolation = EditorScrollIsolation()
        var calls = 0
        val delegate = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { calls++; return available }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset { calls++; return available }
            override suspend fun onPreFling(available: Velocity): Velocity { calls++; return available }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { calls++; return available }
        }
        val gated = isolation.gate(delegate)
        isolation.pageGestureStarted()
        isolation.editorGestureStarted()
        assertTrue(isolation.editorOwnsGesture)
        val offset = Offset(12f, -30f)
        val velocity = Velocity(20f, -100f)
        assertEquals(Offset.Zero, gated.onPreScroll(offset, NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, gated.onPostScroll(offset, offset, NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, gated.onPreFling(velocity))
        assertEquals(Velocity.Zero, gated.onPostFling(velocity, velocity))
        assertEquals(0, calls)
        isolation.pageGestureStarted()
        assertFalse(isolation.editorOwnsGesture)
        assertEquals(offset, gated.onPreScroll(offset, NestedScrollSource.UserInput))
        assertEquals(offset, gated.onPostScroll(offset, offset, NestedScrollSource.UserInput))
        assertEquals(velocity, gated.onPreFling(velocity))
        assertEquals(velocity, gated.onPostFling(velocity, velocity))
        assertEquals(4, calls)
    }

    @Test
    fun remainderConsumesOnlyVerticalScrollAndFling() = runBlocking {
        assertEquals(Offset(0f, -30f), EditorScrollRemainder.onPostScroll(Offset.Zero, Offset(12f, -30f), NestedScrollSource.UserInput))
        assertEquals(Velocity(0f, 100f), EditorScrollRemainder.onPostFling(Velocity.Zero, Velocity(20f, 100f)))
    }
}
