package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.*
import org.junit.Test

class GptSpeedModePolicyTest {
    @Test fun switchingAwayAndBackDoesNotRestoreSpeed() {
        for (active in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST)) {
            val away = GptSpeedModePolicy.forBinding(active, "deepseek-chat")
            assertEquals(GptSpeedMode.NORMAL, away)
            assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(away, "gpt-6-astra"))
        }
    }

    @Test fun missingModelUnsupportedProtocolAndNonTextModelsReset() {
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, ""))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, "claude-opus"))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, "gpt-6-astra", false))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, "gpt-image-1"))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.cycle(GptSpeedMode.NORMAL, "grok-4"))
    }

    @Test fun normalFastUltraNormalKeepsReasoningIndependent() {
        val first = GptSpeedModePolicy.cycle(GptSpeedMode.NORMAL, "gpt-6-astra")
        val second = GptSpeedModePolicy.cycle(first, "gpt-6-astra")
        assertEquals(GptSpeedMode.FAST, first)
        assertEquals(GptSpeedMode.ULTRA_FAST, second)
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.cycle(second, "gpt-6-astra"))
        val raw = config().copy(reasoningEffort = ReasoningEffort.HIGH, thinkingEnabled = true)
        val frozen = GptSpeedModePolicy.snapshot(raw, first)
        val later = GptSpeedModePolicy.snapshot(raw, second)
        assertEquals(GptSpeedMode.FAST, frozen.gptSpeedMode)
        assertEquals(GptSpeedMode.ULTRA_FAST, later.gptSpeedMode)
        assertEquals(ReasoningEffort.HIGH, frozen.reasoningEffort)
        assertNull(raw.gptSpeedMode)
    }

    @Test fun snapshotNeverCarriesTheOldTierIntoAnotherModelOrMedia() {
        val fast = GptSpeedModePolicy.snapshot(config(), GptSpeedMode.FAST)
        assertNull(GptSpeedModePolicy.snapshot(fast.copy(model = "deepseek-chat"), GptSpeedMode.FAST).gptSpeedMode)
        assertNull(GptSpeedModePolicy.snapshot(fast.copy(providerType = ProviderTypes.ANTHROPIC), GptSpeedMode.FAST).gptSpeedMode)
        assertNull(GptSpeedModePolicy.snapshot(fast, GptSpeedMode.FAST, false).gptSpeedMode)
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.snapshot(config(), GptSpeedMode.NORMAL).gptSpeedMode)
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "", model = "gpt-6-astra", systemPrompt = "",
    )
}
