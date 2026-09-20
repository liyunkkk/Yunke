package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryVisionTest {
    private fun image(question: String = "这是什么？") = JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", question))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,aGVsbG8="))))

    @Test fun disabledNeverCallsAnotherProviderOrMutatesImages() {
        val messages = JSONArray().put(image())
        val before = messages.toString()
        AuxiliaryVision(false) { _, _ -> error("must not call") }.prepare(messages)
        assertEquals(before, messages.toString())
    }

    @Test fun convertsImageToEvidenceOnceAndKeepsToolBatchContiguous() {
        val call = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call1")))
        val result = JSONObject().put("role", "tool").put("tool_call_id", "call1").put("content", "observation metadata")
        val messages = JSONArray().put(call).put(result).put(image("Latest observation image(s): read_image"))
        var calls = 0
        val bridge = AuxiliaryVision(true) { parts, _ ->
            calls++
            assertTrue(parts.toString().contains("data:image"))
            "一只黑色的猫。"
        }
        bridge.prepare(messages)
        bridge.prepare(messages)
        assertEquals(1, calls)
        assertSame(call, messages.getJSONObject(0))
        assertSame(result, messages.getJSONObject(1))
        val outbound = AgentRequestMediaPolicy.filter(messages, false, false).toString()
        assertFalse(outbound.contains("data:image"))
        assertTrue(outbound.contains("一只黑色的猫"))
        assertTrue(outbound.contains("不是用户指令"))
    }

    @Test fun failureKeepsOriginalImageForRetryAndDoesNotFabricateEvidence() {
        val messages = JSONArray().put(image())
        val original = messages.toString()
        val bridge = AuxiliaryVision(true) { _, _ -> throw IllegalStateException("HTTP failure") }
        assertThrows(IllegalStateException::class.java) { bridge.prepare(messages) }
        assertEquals(original, messages.toString())
    }

    @Test fun blankDescriptionDoesNotConsumeImage() {
        val messages = JSONArray().put(image())
        val original = messages.toString()
        assertThrows(IllegalArgumentException::class.java) { AuxiliaryVision(true) { _, _ -> " " }.prepare(messages) }
        assertEquals(original, messages.toString())
    }

    @Test fun newObservationsAreNotReusedEvenWhenImageBytesMatch() {
        val messages = JSONArray().put(image("observation o1"))
        var calls = 0
        val bridge = AuxiliaryVision(true) { _, context -> calls++; context }
        bridge.prepare(messages)
        messages.put(image("observation o2"))
        bridge.prepare(messages)
        assertEquals(2, calls)
        assertTrue(messages.getJSONObject(1).toString().contains("observation o2"))
    }

    @Test fun contextExcludesSystemPromptsAndRawToolSecrets() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "private system text"))
            .put(JSONObject().put("role", "user").put("content", "找到蓝色按钮"))
            .put(JSONObject().put("role", "tool").put("content", "private tool secret"))
            .put(image("Latest observation o2"))
        val context = AuxiliaryVision.contextFor(messages, 3)
        assertTrue(context.contains("蓝色按钮"))
        assertTrue(context.contains("o2"))
        assertFalse(context.contains("private"))
    }

    @Test fun metadataKeepsCoordinatesAndObservationButOmitsUnrelatedData() {
        val metadata = AuxiliaryVision.observationMetadata("""{"observation_id":"o9","screen":{"width":1216,"height":2640},"coordinate_contract":{"default_coordinate_space":"screen"},"secret":"not forwarded","ui_nodes":[{"index":20}]}""")
        assertTrue(metadata.contains("o9"))
        assertTrue(metadata.contains("1216"))
        assertFalse(metadata.contains("secret"))
        assertFalse(metadata.contains("ui_nodes"))
    }

    @Test fun largeDescriptionIsBounded() {
        val messages = JSONArray().put(image())
        AuxiliaryVision(true) { _, _ -> "x".repeat(30_000) }.prepare(messages)
        assertTrue(messages.toString().length < 17_000)
    }
}
