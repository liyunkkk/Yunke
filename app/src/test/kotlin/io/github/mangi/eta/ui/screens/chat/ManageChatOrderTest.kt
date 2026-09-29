package io.github.mangi.eta.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ManageChatOrderTest {
    @Test
    fun runningUpdatesDoNotReorderExistingChats() {
        val opened = listOf("running-a", "running-b", "idle")
        val afterActivity = listOf("running-b", "running-a", "idle")
        assertEquals(opened, stableManageChatOrder(opened, afterActivity))
    }

    @Test
    fun newChatAppearsInFrontWithoutMovingTheRest() {
        val opened = listOf("running-a", "idle")
        val withNew = listOf("fresh", "running-a", "idle")
        assertEquals(listOf("fresh", "running-a", "idle"), stableManageChatOrder(opened, withNew))
    }

    @Test
    fun deletedChatDropsOutAndLeavesNeighbors() {
        assertEquals(
            listOf("running-a", "idle"),
            stableManageChatOrder(listOf("running-a", "gone", "idle"), listOf("idle", "running-a")),
        )
    }

    @Test
    fun pinChangeUsesTheIncomingOrder() {
        val opened = listOf("a", "b")
        val pinned = listOf("b", "a")
        assertEquals(pinned, stableManageChatOrder(opened, pinned, reorder = true))
    }
}
