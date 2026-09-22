package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaReasoningSettingsTest {
    private fun config(body: String = "") = AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="test",model="not-inferred",systemPrompt="",extraBodyJson=body)
    private fun spec(role: String="image_generation", field: String="reasoning_effort", values: String="""{"off":"none","low":"low","high":"high"}""") =
        """{"keep":42,"eta_media_reasoning":{"$role":{"field":"$field","values":$values,"default":"low"}}}"""
    @Test fun chatCapabilityDoesNotEnableMediaThinking() {
        val cfg=config().copy(reasoningCapabilities=ModelReasoningCapabilities(supportedEfforts=listOf(ReasoningEffort.HIGH)))
        assertEquals(MediaReasoningSettings.Status.UNSUPPORTED,MediaReasoningSettings.resolve(cfg,"image_generation").status)
        assertThrows(IllegalArgumentException::class.java) { MediaReasoningSettings.apply(cfg,"image_generation",ReasoningEffort.HIGH) }
        assertFalse(JSONObject(MediaReasoningSettings.apply(cfg,"image_generation",null).extraBodyJson).has("reasoning_effort"))
    }
    @Test fun imageAndVideoHaveIndependentContracts() {
        val cfg=config(spec())
        assertEquals(MediaReasoningSettings.Status.SUPPORTED,MediaReasoningSettings.resolve(cfg,"image_generation").status)
        assertEquals(MediaReasoningSettings.Status.UNSUPPORTED,MediaReasoningSettings.resolve(cfg,"video_generation").status)
        assertThrows(IllegalArgumentException::class.java) { MediaReasoningSettings.apply(cfg,"video_generation",ReasoningEffort.LOW) }
    }
    @Test fun mapsOverridesWithoutMutatingSharedModelOrLeakingMetadata() {
        val original=spec();val cfg=config(original)
        val high=MediaReasoningSettings.apply(cfg,"image_generation",ReasoningEffort.HIGH)
        val off=MediaReasoningSettings.apply(cfg,"image_generation",ReasoningEffort.OFF)
        assertEquals("high",JSONObject(high.extraBodyJson).getString("reasoning_effort"))
        assertEquals("none",JSONObject(off.extraBodyJson).getString("reasoning_effort"))
        assertEquals(42,JSONObject(high.extraBodyJson).getInt("keep"))
        assertFalse(JSONObject(high.extraBodyJson).has(MediaReasoningSettings.CONFIG_KEY))
        assertEquals(original,cfg.extraBodyJson)
        assertEquals("low",JSONObject(MediaReasoningSettings.apply(cfg,"image_generation",null).extraBodyJson).getString("reasoning_effort"))
    }
    @Test fun invalidOrStaleEffortIsNotSilentlyNormalized() {
        val cfg=config(spec())
        assertThrows(IllegalArgumentException::class.java) { MediaReasoningSettings.apply(cfg,"image_generation",ReasoningEffort.XHIGH) }
        for (bad in listOf("no json","""{"eta_media_reasoning":true}""",spec(field="prompt"),spec(field="input.secret"),
            spec(field="foo.eta_image_config"),spec(values="""{"low":null}"""),spec(values="""{"low":{}}"""),spec(values="""{"oops":"low"}"""))) {
            assertEquals(bad,MediaReasoningSettings.Status.INVALID,MediaReasoningSettings.resolve(config(bad),"image_generation").status)
            assertThrows(IllegalArgumentException::class.java) { MediaReasoningSettings.apply(config(bad),"image_generation",null) }
        }
    }
    @Test fun nestedBooleanAndBudgetMappingsUseRealTypes() {
        for ((field,values,expected) in listOf(Triple("thinking.enabled","""{"low":true,"off":false}""",true),Triple("thinking.budget","""{"low":1024,"off":0}""",1024))) {
            val applied=JSONObject(MediaReasoningSettings.apply(config(spec(field=field,values=values)),"image_generation",null).extraBodyJson)
            assertEquals(expected,applied.getJSONObject("thinking").get(field.substringAfter('.')))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaReasoningSettings.apply(config(spec(field="thinking.level").replace("\"keep\":42","\"thinking\":false")),"image_generation",null)
        }
    }
    @Test fun customBodySettingsAreReadAndCannotOverrideChosenEffortAgain() {
        val body=JSONObject(spec()).getJSONObject("eta_media_reasoning")
        val cfg=config().copy(customBody=listOf(
            io.github.mangi.eta.data.model.CustomBody(key="eta_media_reasoning",value=kotlinx.serialization.json.Json.parseToJsonElement(body.toString())),
            io.github.mangi.eta.data.model.CustomBody(key="reasoning_effort",value=kotlinx.serialization.json.JsonPrimitive("low"))))
        assertEquals(MediaReasoningSettings.Status.SUPPORTED,MediaReasoningSettings.resolve(cfg,"image_generation").status)
        val result=MediaReasoningSettings.apply(cfg,"image_generation",ReasoningEffort.HIGH)
        assertEquals("high",JSONObject(result.extraBodyJson).getString("reasoning_effort"))
        assertTrue(result.customBody.isEmpty());assertEquals(2,cfg.customBody.size)
    }
    @Test fun videoRequiresPinnedTransportAndMultipartRejectsNestedFields() {
        assertEquals(MediaReasoningSettings.Status.INVALID,MediaReasoningSettings.resolve(config(spec(role="video_generation")),"video_generation").status)
        val video=spec(role="video_generation").replace("\"field\":", "\"transport\":\"videos_json\",\"field\":")
        assertEquals("videos_json",MediaReasoningSettings.resolve(config(video),"video_generation").transport)
        assertEquals("high",JSONObject(MediaReasoningSettings.apply(config(video),"video_generation",ReasoningEffort.HIGH).extraBodyJson).getString("reasoning_effort"))
        val nested=video.replace("videos_json","videos_multipart").replace("reasoning_effort","thinking.level")
        assertEquals(MediaReasoningSettings.Status.INVALID,MediaReasoningSettings.resolve(config(nested),"video_generation").status)
    }

}
