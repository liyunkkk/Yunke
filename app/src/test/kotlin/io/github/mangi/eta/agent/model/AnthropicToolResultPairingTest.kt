package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ProviderTypes
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Anthropic 要求同一批 tool_use 的全部 tool_result 落在紧随 assistant 的同一条 user 消息里，
 * 且 tool_result 排在该消息的 text/image 之前。这里对实际出站 JSON 做回归。
 */
class AnthropicToolResultPairingTest {
    @Test
    fun twoToolResultsFromOneBatchMergeIntoSingleUserMessage() {
        val messages = JSONArray()
            .put(userText("run both"))
            .put(assistantWithCalls("toolu_a" to "terminal", "toolu_b" to "observe_screen"))
            .put(toolResult("toolu_a", "a done"))
            .put(toolResult("toolu_b", "b done"))

        val outbound = outboundMessages(messages)

        assertEquals(listOf("user", "assistant", "user"), roles(outbound))
        val results = outbound.getJSONObject(2).getJSONArray("content")
        assertEquals(2, results.length())
        assertEquals("toolu_a", results.getJSONObject(0).getString("tool_use_id"))
        assertEquals("a done", results.getJSONObject(0).getString("content"))
        assertEquals("toolu_b", results.getJSONObject(1).getString("tool_use_id"))
        assertEquals("b done", results.getJSONObject(1).getString("content"))
    }

    @Test
    fun threeResultsKeepOrderAndFollowingTextAndImageStayAfterToolResults() {
        val messages = JSONArray()
            .put(assistantWithCalls("t1" to "a", "t2" to "b", "t3" to "c"))
            .put(toolResult("t1", "r1"))
            .put(toolResult("t2", "r2"))
            .put(toolResult("t3", "r3"))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "看这张图"))
                        .put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:image/png;base64,QUJD"),
                            )
                        )
                )
            )

        val outbound = outboundMessages(messages)

        assertEquals(listOf("assistant", "user"), roles(outbound))
        val content = outbound.getJSONObject(1).getJSONArray("content")
        assertEquals(
            listOf("tool_result", "tool_result", "tool_result", "text", "image"),
            (0 until content.length()).map { content.getJSONObject(it).getString("type") },
        )
        assertEquals(
            listOf("t1", "t2", "t3"),
            (0 until 3).map { content.getJSONObject(it).getString("tool_use_id") },
        )
        assertEquals(
            listOf("r1", "r2", "r3"),
            (0 until 3).map { content.getJSONObject(it).getString("content") },
        )
        assertEquals("看这张图", content.getJSONObject(3).getString("text"))
        assertEquals("QUJD", content.getJSONObject(4).getJSONObject("source").getString("data"))
    }

    @Test
    fun failedToolResultTextIsPreservedInsideMergedBatch() {
        val messages = JSONArray()
            .put(assistantWithCalls("ok_id" to "terminal", "bad_id" to "terminal"))
            .put(toolResult("ok_id", "exit=0"))
            .put(toolResult("bad_id", "工具执行失败：permission denied"))

        val content = outboundMessages(messages).getJSONObject(1).getJSONArray("content")

        assertEquals(2, content.length())
        assertEquals("exit=0", content.getJSONObject(0).getString("content"))
        assertEquals("bad_id", content.getJSONObject(1).getString("tool_use_id"))
        assertEquals("工具执行失败：permission denied", content.getJSONObject(1).getString("content"))
    }

    @Test
    fun singleToolCallStillProducesOneUserMessageWithOneResult() {
        val messages = JSONArray()
            .put(assistantWithCalls("only" to "terminal"))
            .put(toolResult("only", "done"))

        val outbound = outboundMessages(messages)

        assertEquals(listOf("assistant", "user"), roles(outbound))
        val content = outbound.getJSONObject(1).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("only", content.getJSONObject(0).getString("tool_use_id"))
        assertEquals("done", content.getJSONObject(0).getString("content"))
    }

    @Test
    fun toolFreeConversationKeepsMessagesAndContentUnchanged() {
        val messages = JSONArray()
            .put(userText("hi"))
            .put(JSONObject().put("role", "assistant").put("content", "hello"))
            .put(userText("thanks"))

        val outbound = outboundMessages(messages)

        assertEquals(listOf("user", "assistant", "user"), roles(outbound))
        assertEquals("hi", firstText(outbound.getJSONObject(0)))
        assertEquals("hello", firstText(outbound.getJSONObject(1)))
        assertEquals("thanks", firstText(outbound.getJSONObject(2)))
    }

    @Test
    fun normalizationDoesNotMutateInputHistory() {
        val messages = JSONArray()
            .put(assistantWithCalls("x1" to "terminal", "x2" to "terminal"))
            .put(toolResult("x1", "r1"))
            .put(toolResult("x2", "r2"))
        val before = messages.toString()

        outboundMessages(messages)

        assertEquals(before, messages.toString())
    }

    @Test
    fun missingToolResultIsRejectedLocallyWithoutSendingRequest() {
        val messages = JSONArray()
            .put(assistantWithCalls("present" to "terminal", "absent" to "terminal"))
            .put(toolResult("present", "r1"))

        val failure = expectPairingFailure(messages)

        assertTrue(failure.diagnostic.contains("calls=2"))
        assertTrue(failure.diagnostic.contains("missing=1"))
        // 诊断只带统计与位置，不含工具输出正文。
        assertFalse(failure.diagnostic.contains("r1"))
    }

    @Test
    fun orphanToolResultWithoutPrecedingToolUseIsRejected() {
        val messages = JSONArray()
            .put(userText("hi"))
            .put(toolResult("ghost", "r"))

        val failure = expectPairingFailure(messages)

        assertTrue(failure.diagnostic.contains("origin=provider"))
        assertTrue(failure.diagnostic.contains("孤立 tool_result"))
    }

    @Test
    fun duplicateToolResultInSameBatchIsRejected() {
        val messages = JSONArray()
            .put(assistantWithCalls("dup" to "terminal"))
            .put(toolResult("dup", "first"))
            .put(toolResult("dup", "second"))

        assertTrue(expectPairingFailure(messages).diagnostic.contains("duplicate_ids=1"))
    }

    @Test
    fun blankToolResultIdIsRejected() {
        val messages = JSONArray()
            .put(assistantWithCalls("real" to "terminal"))
            .put(toolResult("", "r"))

        assertTrue(expectPairingFailure(messages).diagnostic.contains("空 tool_use_id"))
    }

    @Test
    fun customBodyCannotOverrideMessagesWithInvalidSequence() {
        val messages = JSONArray()
            .put(assistantWithCalls("keep" to "terminal"))
            .put(toolResult("keep", "r"))
        // 自定义请求体直接给出 Anthropic 形状的 messages：assistant 有 tool_use，后续 user 没有 tool_result。
        val overridden = JSONArray()
            .put(
                JSONObject().put("role", "assistant").put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", "keep")
                            .put("name", "terminal")
                            .put("input", JSONObject())
                    )
                )
            )
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "text").put("text", "no results here"))
                )
            )

        val failure = expectPairingFailure(
            messages = messages,
            customBody = listOf(
                CustomBody(key = "messages", value = Json.parseToJsonElement(overridden.toString()))
            ),
        )

        assertTrue(failure.diagnostic.contains("origin=final_request"))
    }

    @Test
    fun customBodyKeepsIntentionalNonToolMessagesUntouched() {
        val overridden = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", "custom only"))
            )
        )

        val outbound = outboundMessages(
            messages = JSONArray().put(userText("ignored")),
            customBody = listOf(
                CustomBody(key = "messages", value = Json.parseToJsonElement(overridden.toString()))
            ),
        )

        assertEquals(1, outbound.length())
        assertEquals("custom only", firstText(outbound.getJSONObject(0)))
    }

    private fun userText(text: String): JSONObject =
        JSONObject().put("role", "user").put("content", text)

    private fun assistantWithCalls(vararg calls: Pair<String, String>): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put(
                "tool_calls",
                JSONArray().also { array ->
                    calls.forEach { (id, name) ->
                        array.put(
                            JSONObject()
                                .put("id", id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", name).put("arguments", "{}"),
                                )
                        )
                    }
                }
            )

    private fun toolResult(id: String, content: String): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content)

    private fun roles(messages: JSONArray): List<String> =
        (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }

    private fun firstText(message: JSONObject): String =
        message.getJSONArray("content").getJSONObject(0).getString("text")

    /** 用 mock HTTP 捕获真实出站请求体，返回其 messages 数组。 */
    private fun outboundMessages(
        messages: JSONArray,
        customBody: List<CustomBody> = emptyList(),
    ): JSONArray {
        val captured = AtomicReference<String>()
        withAnthropicServer(onRequest = captured::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = config(baseUrl, customBody),
                    messages = messages,
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
            )
        }
        return JSONObject(captured.get()).getJSONArray("messages")
    }

    private fun expectPairingFailure(
        messages: JSONArray,
        customBody: List<CustomBody> = emptyList(),
    ): AgentModelFailure {
        val requests = AtomicInteger(0)
        val captured = AtomicReference<AgentModelFailure?>(null)
        withAnthropicServer(onRequest = { requests.incrementAndGet() }) { baseUrl ->
            try {
                AnthropicMessagesProvider.complete(
                    request = ProviderRequest(
                        config = config(baseUrl, customBody),
                        messages = messages,
                        tools = JSONArray(),
                    ),
                    runController = AgentRunController(),
                )
                fail("expected the pairing guard to reject this request")
            } catch (failure: AgentModelFailure) {
                captured.set(failure)
            }
        }
        val failure = requireNotNull(captured.get()) { "no pairing failure captured" }
        assertEquals("ANTHROPIC_TOOL_PAIRING_INVALID", failure.code)
        assertFalse(failure.retryable)
        // 本地拦截：不得向服务端发出请求，也不得重放工具。
        assertEquals(0, requests.get())
        return failure
    }

    private fun config(baseUrl: String, customBody: List<CustomBody>) =
        AgentModelClient.ModelConfig(
            providerType = ProviderTypes.ANTHROPIC,
            baseUrl = baseUrl,
            apiKey = "key",
            model = "claude-sonnet-5",
            systemPrompt = "system",
            customBody = customBody,
        )

    private fun withAnthropicServer(onRequest: (String) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val body = "event: message_stop\ndata: ${JSONObject().put("type", "message_stop")}\n\n"
        server.createContext("/v1/messages") { exchange ->
            onRequest(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
