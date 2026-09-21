package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.data.db.ProviderWithModels
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import org.junit.Assert.*
import org.junit.Test

class SpeechProviderRoundTripTest {
    @Test fun genericProviderWithUuidModelIdsSurvivesStorageAndOffersCorrectVoices() {
        val provider = CustomProviderSetting(
            id = "relay", name = "relay", baseUrl = "https://example.com/v1", apiKey = "test",
            models = listOf(Model("uuid-cosy", "CosyVoice2", "CosyVoice2"),
                Model("uuid-moss", "MOSS-TTSD", "MOSS-TTSD"), Model("uuid-chat", "chat", "chat")),
        )
        val restored = ProviderWithModels(provider.toEntity(), provider.toModelEntities()).toDomain()
        for ((id, engine, prefix) in listOf(
            Triple("uuid-cosy", SpeechEngine.COSYVOICE, "FunAudioLLM/CosyVoice2-0.5B:"),
            Triple("uuid-moss", SpeechEngine.MOSS, "fnlp/MOSS-TTSD-v0.5:"),
        )) {
            val picker = AgentModelPickerProjector.project(listOf(restored), "relay", id, speechOnly = true)
            val selected = requireNotNull(picker.selectedModel)
            assertEquals(engine, SpeechEngineResolver.resolve(restored, selected.modelId))
            val voices = SpeechVoices.catalog(engine, selected.modelId)
            assertEquals(8, voices.size)
            assertTrue(voices.all { it.id.startsWith(prefix) })
            assertEquals(listOf("CosyVoice2", "MOSS-TTSD"), picker.providerGroups.single().models.map { it.modelId })
        }
        val chat = AgentModelPickerProjector.project(listOf(restored), "relay", "uuid-chat")
        assertEquals(listOf("chat"), chat.providerGroups.single().models.map { it.modelId })
    }

    @Test fun oldDedicatedProviderRemainsUsableAfterStorageWithoutSourceType() {
        val old = CustomProviderSetting(id = "old", name = "语音合成", baseUrl = "https://example.com/v1",
            apiKey = "test", sourceType = ProviderSourceTypes.COMPATIBLE_SPEECH)
        val seeded = old.copy(models = SpeechSynthesisModels.catalogModels(old))
        val restored = ProviderWithModels(seeded.toEntity(), seeded.toModelEntities()).toDomain()
        assertTrue(SpeechSynthesisModels.isReadAloudProvider(restored))
        assertEquals(2, AgentModelPickerProjector.project(listOf(restored), "old", "CosyVoice2",
            speechOnly = true).providerGroups.single().models.size)
    }
}
