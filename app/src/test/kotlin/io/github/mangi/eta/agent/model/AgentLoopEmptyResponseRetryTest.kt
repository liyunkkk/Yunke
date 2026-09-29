package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空响应重试契约。
 *
 * 现场故障：`模型接口第 8 轮返回为空：step` 直接判失败。这里钉死：
 * - 非截断的空响应（如 finish_reason=step）有界重试，重试成功后正常返回；
 * - 重试耗尽后错误文案带上次数、finish_reason 与模型；
 * - 原有「输出上限截断」路径的次数与文案保持不变，且不发送 EMPTY_RESPONSE 事件。
 */
class AgentLoopEmptyResponseRetryTest {

    @Test
    fun emptyResponseWithStepFinishReasonIsRetriedThenSucceeds() {
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val provider = fixedProvider { if (calls++ == 0) empty("step") else reply("done") }

        val result = run(provider, events).run()

        assertEquals("done", result.content)
        assertEquals(2, calls)
        val retries = events.filterIsInstance<AgentEvent.ModelRetryScheduled>()
        assertEquals(1, retries.size)
        assertEquals("EMPTY_RESPONSE", retries.single().reasonCode)
        assertTrue(retries.single().reasonDetail.contains("step"))
    }

    @Test
    fun persistentlyEmptyResponseFailsAfterTwoRetries() {
        var calls = 0
        val provider = fixedProvider { calls++; empty("step") }

        val failure = assertThrows(IllegalStateException::class.java) { run(provider).run() }

        // 首次请求 + 最多 2 次重发。
        assertEquals(3, calls)
        val message = failure.message.orEmpty()
        assertTrue(message.contains("已重试 2 次仍为空"))
        assertTrue(message.contains("step"))
        assertTrue(message.contains("test-model"))
    }

    @Test
    fun truncationWithoutBodyKeepsItsOwnBoundedRetriesAndMessage() {
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val provider = fixedProvider { calls++; empty("length") }

        val failure = assertThrows(IllegalStateException::class.java) { run(provider, events).run() }

        assertEquals(3, calls)
        assertTrue(failure.message.orEmpty().contains("输出上限处截断"))
        // 截断路径与空响应重试语义独立：不发送 EMPTY_RESPONSE 事件。
        assertTrue(events.filterIsInstance<AgentEvent.ModelRetryScheduled>().none { it.reasonCode == "EMPTY_RESPONSE" })
    }

    private fun run(provider: AgentProviderClient, events: MutableList<AgentEvent> = mutableListOf()) =
        AgentLoop(
            config(),
            JSONArray().put(AgentConversationCodec.userTextMessage("任务")),
            JSONArray(),
            provider,
            AgentModelClient.ToolExecutor { error("No tools") },
            AgentRunController(),
            AgentTraceFormatter(),
            systemCount = 1,
            onEvent = events::add,
        )

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test-model", systemPrompt = "",
    )

    private fun empty(finishReason: String) = JSONObject()
        .put("role", "assistant").put("content", "").put("finish_reason", finishReason)

    private fun reply(text: String) = JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", "stop")

    private fun fixedProvider(block: () -> JSONObject) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities =
            ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = ProviderResponse(block())
    }
}
