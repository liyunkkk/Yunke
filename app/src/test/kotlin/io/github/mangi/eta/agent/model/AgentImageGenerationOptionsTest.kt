package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentImageGenerationOptionsTest {
    @Test fun grokUsesAspectAndResolutionAndOverridesConfiguredSquare() {
        val defaults = JSONObject().put("size", "1024x1024").put("aspect_ratio", "1:1").put("resolution", "1k")
        val options = AgentImageGenerationOptions.fromJson(JSONObject("""{"aspect_ratio":"9：16","resolution":"2K","n":2,"quality":"medium","response_format":"b64_json"}"""))
        options.applyTo(defaults, "grok-imagine-image-2.0")
        assertEquals("9:16", defaults.getString("aspect_ratio"))
        assertEquals("2k", defaults.getString("resolution"))
        assertFalse(defaults.has("size"))
        assertEquals(2, defaults.getInt("n"))
        assertEquals("medium", defaults.getString("quality"))
        assertEquals("b64_json", defaults.getString("response_format"))
    }
    @Test fun allAdvertisedGrokRatiosArePreserved() {
        AgentImageGenerationOptions.aspectRatios.forEach { ratio ->
            val body = JSONObject()
            AgentImageGenerationOptions(aspectRatio = ratio).applyTo(body, "grok-imagine-image-2.0")
            assertEquals(ratio, body.getString("aspect_ratio"))
        }
    }
    @Test fun rejectsGrokPixelSizeAndInvalidOptionsRatherThanGuessing() {
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(size = "1080x1920").applyTo(JSONObject(), "grok-imagine-image-2.0")
        }
        listOf("""{"n":1.5}""", """{"n":11}""", """{"aspect_ratio":"0:0"}""", """{"resolution":"4k"}""",
            """{"size":"0x0"}""", """{"resolution":null}""", """{"prompt":"override"}""").forEach { json ->
            assertThrows(ImageGenerationParameterException::class.java) { AgentImageGenerationOptions.fromJson(JSONObject(json)) }
        }
    }
    @Test fun gpt2MapsExactRatioButGpt1NeverApproximatesNineSixteen() {
        val body = JSONObject().put("aspect_ratio", "1:1").put("resolution", "1k")
        AgentImageGenerationOptions(aspectRatio = "9:16").applyTo(body, "gpt-image-2")
        assertEquals("864x1536", body.getString("size"))
        assertFalse(body.has("aspect_ratio")); assertFalse(body.has("resolution"))
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(aspectRatio = "9:16").applyTo(JSONObject(), "gpt-image-1")
        }
    }
    @Test fun conflictingShapesUnsupportedQualityAndPixelLimitsFailBeforeNetwork() {
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(aspectRatio = "9:16", size = "1024x1024").applyTo(JSONObject(), "gpt-image-2")
        }
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(quality = "high").applyTo(JSONObject(), "grok-imagine-image-2.0")
        }
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(size = "1080x1920").applyTo(JSONObject(), "gpt-image-2")
        }
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(aspectRatio = "9:16").applyTo(JSONObject(), "unknown-image-model")
        }
    }
    @Test fun explicitRatioWithAutoSizeIsRejectedBeforePaidRequest() {
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationOptions(aspectRatio = "9:16", size = "auto").applyTo(JSONObject(), "gpt-image-2")
        }
    }
    @Test fun actualDimensionsExposeIgnoredAspectAndResolution() {
        val options = AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k")
        assertTrue(options.dimensionReport(1024, 1024).contains("IMAGE_DIMENSIONS_MISMATCH"))
        assertTrue(options.dimensionReport(1152, 2048).contains("实际尺寸：1152x2048"))
        assertFalse(options.dimensionReport(1152, 2048).contains("MISMATCH"))
        assertTrue(options.dimensionReport(-1, -1).contains("IMAGE_DIMENSIONS_UNVERIFIED"))
        assertTrue(AgentImageGenerationOptions(size = "864x1536").dimensionReport(900, 1600).contains("MISMATCH"))
    }
}
