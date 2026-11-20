package io.github.mangi.eta.agent.model.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAntigravityOAuthTest {
    @Test
    fun cloudcodeHostIsAntigravityEndpoint() {
        assertTrue(GoogleAntigravityOAuth.isAntigravityEndpoint("https://cloudcode-pa.googleapis.com"))
        assertEquals("2.33.0", GoogleAntigravityOAuth.CLIENT_VERSION)
        assertTrue(GoogleAntigravityOAuth.isAntigravityEndpoint("https://daily-cloudcode-pa.googleapis.com"))
        assertTrue(GoogleAntigravityOAuth.isAntigravityEndpoint("https://cloudcode-pa.googleapis.com/"))
        assertFalse(GoogleAntigravityOAuth.isAntigravityEndpoint("https://chatgpt.com/backend-api/codex"))
        assertFalse(GoogleAntigravityOAuth.isAntigravityEndpoint("https://api.openai.com/v1"))
        assertTrue(GoogleAntigravityOAuth.requestUserAgent().startsWith("antigravity/hub/2.33.0 (aidev_client; os_type=darwin; arch=arm64; cl=1023160026)"))
    }

    @Test
    fun parseModelsReadsObjectCatalog() {
        val models = GoogleAntigravityOAuth.parseModels(
            """{"models":{"gemini-3-flash":{"displayName":"Gemini 3 Flash","model":"gemini-3-flash"},"claude-sonnet-4-6":{"displayName":"Claude Sonnet 4.6","model":"claude-sonnet-4-6"}}}"""
        )
        assertEquals(listOf("gemini-3-flash", "claude-sonnet-4-6"), models.map { it.modelId })
        assertEquals("Gemini 3 Flash", models.first().displayName)
    }

    @Test
    fun objectCatalogUsesCallableKeysInsteadOfInternalModelEnums() {
        val models = GoogleAntigravityOAuth.parseModels(
            """{"models":{
              "gemini-3-flash":{"displayName":"Gemini 3 Flash","model":"MODEL_PLACEHOLDER_M18"},
              "gemini-3.1-pro-high":{"displayName":"Gemini 3.1 Pro (High)","model":"MODEL_PLACEHOLDER_M37"},
              "claude-sonnet-4-6":{"displayName":"Claude Sonnet 4.6 (Thinking)","model":"MODEL_PLACEHOLDER_M35"},
              "gpt-oss-120b-medium":{"model":"MODEL_OPENAI_GPT_OSS_120B_MEDIUM"}
            }}"""
        )
        assertEquals(setOf("gemini-3-flash", "gemini-3.1-pro-high", "claude-sonnet-4-6", "gpt-oss-120b-medium"),
            models.map { it.modelId }.toSet())
        assertTrue(models.none { it.modelId.startsWith("MODEL_") })
    }

    @Test
    fun parseModelsKeepsUltraTieredModelsBeyondFlashDeclaration() {
        val models = GoogleAntigravityOAuth.parseModels(
            """{"tieredModelIds":{"flash":["gemini-3.8-flash-high"],"pro":["gemini-4-hydrogen-xhigh"]},"models":{"gemini-3.8-flash-high":{"displayName":"Gemini 3.8 Flash High"},"gemini-4-hydrogen-xhigh":{"displayName":"Gemini 4 Hydrogen XHigh"},"chat_internal_preview":{"displayName":"Chat internal"}}}""",
        )
        assertEquals(setOf("gemini-3.8-flash-high", "gemini-4-hydrogen-xhigh"), models.map { it.modelId }.toSet())
        val hydrogen = models.single { it.modelId == "gemini-4-hydrogen-xhigh" }
        assertTrue(hydrogen.reasoningCapabilities?.supportedEfforts?.contains(io.github.mangi.eta.data.model.ReasoningEffort.XHIGH) == true)
    }

    @Test
    fun defaultModelsIncludeCurrentGemini4AndUltraEntries() {
        val ids = GoogleAntigravityOAuth.defaultModels().map { it.modelId }.toSet()
        assertTrue("gemini-4-hydrogen-xhigh" in ids)
        assertTrue("gemini-4-argon-high" in ids)
        assertTrue("gemini-3.8-flash-high" in ids)
    }

    @Test
    fun legacyProNameDoesNotPretendToBeNewerModel() {
        assertEquals("Gemini 3 Pro (High)", GoogleAntigravityOAuth.prettyModelName("gemini-3-pro-high", ""))
        assertEquals("Gemini 3.1 Pro (High)", GoogleAntigravityOAuth.prettyModelName("gemini-3.1-pro-high", ""))
    }

    @Test
    fun filtersPlaceholderAndImageModels() {
        val models = GoogleAntigravityOAuth.parseModels(
            """{"models":{
              "gemini-3-flash":{"displayName":"Gemini 3 Flash","model":"gemini-3-flash"},
              "MODEL_PLACEHOLDER_1":{"displayName":"MODEL_PLACEHOLDER_1","model":"MODEL_PLACEHOLDER_1"},
              "gemini-3.1-flash-image":{"displayName":"Gemini 3.1 Flash Image","model":"gemini-3.1-flash-image"}
            }}"""
        )
        assertEquals(listOf("gemini-3-flash"), models.map { it.modelId })
        assertFalse(GoogleAntigravityOAuth.isUsableModel("MODEL_PLACEHOLDER_foo"))
        assertTrue(GoogleAntigravityOAuth.isUsableModel("gemini-3-pro-high", "Gemini 3 Pro High"))
    }

    @Test
    fun defaultModelsCoverCurrentLineup() {
        val ids = GoogleAntigravityOAuth.defaultModels().map { it.modelId }
        assertTrue(ids.contains("gemini-3.1-flash") || ids.contains("gemini-3-flash"))
        assertTrue(ids.contains("claude-sonnet-4-6"))
        assertTrue(ids.contains("gemini-3.1-pro") || ids.contains("gemini-3-pro-high"))
        assertTrue(GoogleAntigravityOAuth.defaultModels().all { it.toolCall == true })
    }
}
