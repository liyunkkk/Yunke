package io.github.mangi.eta.agent.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleModelTest {
    @Test fun disabledCustomSelectionUsesExactCurrentModelEvenWithStaleIds() = runBlocking {
        val config = AgentModelClient.ModelConfig(providerType = "openai", baseUrl = "https://example.com/v1",
            apiKey = "test", model = "current", systemPrompt = "")
        assertSame(config, ConversationTitleModel.resolve(ModelFeatureSelection(false, "deleted", "stale"), config))
    }

    @Test fun customMissingSelectionDoesNotSilentlyUseCurrentModel() {
        val config = AgentModelClient.ModelConfig(providerType = "openai", baseUrl = "https://example.com/v1",
            apiKey = "test", model = "current", systemPrompt = "")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ConversationTitleModel.resolve(ModelFeatureSelection(true, "", ""), config) }
        }
    }

    @Test fun lateResponseCannotOverwriteRenameDeleteOrEditedFirstMessage() {
        assertTrue(ConversationTitleModel.mayApply(true, false, "old", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(false, false, "old", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(true, true, "old", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(true, false, "custom", "old", "m1", "m1"))
        assertFalse(ConversationTitleModel.mayApply(true, false, "old", "old", "m2", "m1"))
    }

    @Test fun titleIsSingleLineUnquotedAndBounded() {
        assertEquals("图像识别", ConversationTitleModel.normalize("  “图像识别”\n解释  "))
        assertEquals(24, ConversationTitleModel.normalize("长".repeat(50)).length)
        assertThrows(IllegalArgumentException::class.java) { ConversationTitleModel.normalize("  ") }
    }
}
