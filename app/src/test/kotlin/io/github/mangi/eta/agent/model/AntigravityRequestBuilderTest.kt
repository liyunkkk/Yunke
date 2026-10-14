package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityRequestBuilderTest {
    @Test
    fun convertsChatMessagesAndTools() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是助手"))
            .put(JSONObject().put("role", "user").put("content", "你好"))
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "device_info").put("description", "设备").put("parameters", JSONObject().put("type", "object")),
            ),
        )
        val request = AntigravityRequestBuilder.build(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://daily-cloudcode-pa.googleapis.com",
                apiKey = "token",
                model = "gemini-3-flash",
                systemPrompt = "系统",
                openAiEndpointMode = OpenAiEndpointMode.ANTIGRAVITY,
            ),
            messages = messages,
            tools = tools,
            projectId = "proj-1",
        )
        assertEquals("proj-1", request.getString("project"))
        assertEquals("gemini-3-flash", request.getString("model"))
        assertEquals("agent", request.getString("requestType"))
        val inner = request.getJSONObject("request")
        assertEquals("user", inner.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertEquals("device_info", inner.getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        assertTrue(inner.getJSONObject("systemInstruction").getJSONArray("parts").length() >= 1)
    }

    @Test
    fun stripsUnsupportedSchemaKeywords() {
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "days",
                    JSONObject()
                        .put("type", "array")
                        .put("uniqueItems", true)
                        .put("minItems", 1)
                        .put("items", JSONObject().put("type", "string").put("enum", JSONArray().put("mon"))),
                ),
            )
        val sanitized = AntigravityRequestBuilder.sanitizeSchema(schema) as JSONObject
        val days = sanitized.getJSONObject("properties").getJSONObject("days")
        assertEquals("array", days.getString("type"))
        assertTrue(days.has("items"))
        assertTrue(!days.has("uniqueItems"))
        assertTrue(!days.has("minItems"))
    }

    @Test
    fun derivesStableNegativeSessionIdFromFirstUserMessage() {
        fun build(firstUser: String): JSONObject {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", firstUser))
            return AntigravityRequestBuilder.build(
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://daily-cloudcode-pa.googleapis.com",
                    apiKey = "token",
                    model = "gemini-3-flash",
                    systemPrompt = "系统",
                    openAiEndpointMode = OpenAiEndpointMode.ANTIGRAVITY,
                ),
                messages = messages,
                tools = JSONArray(),
                projectId = "proj-1",
            )
        }
        val first = build("你好").getJSONObject("request").getString("sessionId")
        val again = build("你好").getJSONObject("request").getString("sessionId")
        val other = build("再见").getJSONObject("request").getString("sessionId")
        assertTrue(first.startsWith("-"))
        assertEquals(first, again)
        assertTrue(first != other)
    }

    @Test
    fun usesImageGenerationFingerprintForImageModels() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "画一只猫"))
        val request = AntigravityRequestBuilder.build(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://daily-cloudcode-pa.googleapis.com",
                apiKey = "token",
                model = "gemini-3.1-flash-image",
                systemPrompt = "系统",
                openAiEndpointMode = OpenAiEndpointMode.ANTIGRAVITY,
            ),
            messages = messages,
            tools = JSONArray(),
            projectId = "proj-1",
        )
        assertEquals("image_gen", request.getString("requestType"))
        assertTrue(request.getString("requestId").startsWith("image_gen/"))
        assertTrue(!request.getJSONObject("request").has("sessionId"))
    }

    @Test
    fun routesPublicModelNamesToRuntimeIds() {
        assertEquals("claude-opus-4-6-thinking", AntigravityModelRouting.runtimeModelId("claude-opus-4-6"))
        assertEquals("gemini-3.7-flash-tiered", AntigravityModelRouting.runtimeModelId("gemini-3.7-flash"))
        assertEquals("gemini-3.7-flash-tiered", AntigravityModelRouting.runtimeModelId(" gemini-3.7-flash "))
        assertEquals("future-model", AntigravityModelRouting.runtimeModelId("future-model"))
    }
}
