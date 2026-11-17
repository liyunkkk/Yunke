package io.github.mangi.eta.data.model

import org.junit.Assert.*
import org.junit.Test

class McpToolSelectionTest {
    private fun server() = McpServerSetting("id", "server", "https://example.com/mcp",
        tools = listOf("a", "b").map { McpToolDefinition(it, inputSchemaJson = "{}") },
        enabledToolNames = setOf("a"))

    @Test fun batchRetainsUnselectedAndRejectsUnknownNames() {
        val enabled = server().withToolSelection(listOf("b", "unknown"), true)
        assertEquals(setOf("a", "b"), enabled.enabledToolNames)
        assertEquals(setOf("b"), enabled.withToolSelection(listOf("a"), false).enabledToolNames)
    }

    @Test fun staleDiscoveryDoesNotRevertNewSelectionOrServerSwitch() {
        val staleDiscovery = server().copy(lastRefreshedAt = 42)
        val latest = server().withToolSelection(listOf("b"), true).copy(enabled = false)
        val merged = latest.withDiscoveredTools(staleDiscovery)
        assertEquals(setOf("a", "b"), merged.enabledToolNames)
        assertFalse(merged.enabled)
        assertEquals(42L, merged.lastRefreshedAt)
    }

    @Test fun discoveryRemovesOnlyVanishedNames() {
        val current = server().withToolSelection(listOf("b"), true)
        val refreshed = server().copy(tools = listOf(McpToolDefinition("b", inputSchemaJson = "{}")))
        assertEquals(setOf("b"), current.withDiscoveredTools(refreshed).enabledToolNames)
    }
}
