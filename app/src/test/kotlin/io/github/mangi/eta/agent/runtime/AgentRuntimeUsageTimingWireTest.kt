package io.github.mangi.eta.agent.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 展示用耗时字段的 IPC 兼容性：新帧能往返，旧帧不能炸。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeUsageTimingWireTest {
    private val timings = AgentTokenUsage(
        contextTokens = 32_000,
        inputTokens = 1_500,
        outputTokens = 120,
        reasoningTokens = 40,
        cachedTokens = 1_000,
        cacheCreationTokens = 100,
        stepCount = 3,
        llmMs = 8_400L,
        toolMs = 2_100L,
        ttftMs = 1_200L,
        ttftSteps = 3,
        decodeMs = 6_000L,
        decodeTokens = 120,
    )

    @Test fun timingsSurviveBinderAndDurableReplay() {
        val event = AgentEvent.UsageReceived(round = 4, usage = timings)
        val overBundle = AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event))
        assertEquals(event, overBundle)
        // 存档/断点重放走 Bundle→JSON→Bundle，long 会被 JSON 解析成小整数。
        val overJson = AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event))
        assertEquals(event, overJson)
    }

    @Test fun legacyFrameWithoutTimingKeysStillDecodes() {
        val legacy = AgentTokenUsage(contextTokens = 1_000, inputTokens = 900, outputTokens = 12)
        val decoded = AgentRuntimeWire.eventFromBundle(
            AgentRuntimeWire.eventToBundle(AgentEvent.UsageReceived(round = 1, usage = legacy)),
        ) as AgentEvent.UsageReceived
        assertEquals(legacy, decoded.usage)
        assertNull(decoded.usage.stepCount)
        assertNull(decoded.usage.llmMs)
        assertNull(decoded.usage.toolMs)
        assertNull(decoded.usage.decodeMs)
    }

    @Test fun jsonReplayOfLegacyPayloadKeepsTimingsNull() {
        // 旧版本写下的 JSON：只有 token 字段，没有 usage_*_ms。
        val legacyJson = JSONObject()
            .put("type", "usage_received")
            .put("round", 2)
            .put("projected", false)
            .put("usage_context_tokens", 800)
            .put("usage_input_tokens", 700)
            .toString()
        val decoded = AgentEventJsonCodec.decode(legacyJson) as AgentEvent.UsageReceived
        assertTrue(decoded.round == 2)
        assertTrue(decoded.usage.hasAnyToken())
        assertNull(decoded.usage.llmMs)
        assertNull(decoded.usage.toolMs)
    }

    @Test fun smallMillisecondValuesSurviveJsonNumberNarrowing() {
        // JSONObject(String) 会把 1234 这种小整数解析成 Int，读取端必须兼容，不能 getLong 强转。
        val json = JSONObject()
            .put("type", "usage_received")
            .put("round", 1)
            .put("usage_llm_ms", 1_234L)
            .put("usage_tool_ms", 55L)
            .put("usage_step_count", 2)
            .toString()
        val decoded = AgentEventJsonCodec.decode(json) as AgentEvent.UsageReceived
        assertEquals(1_234L, decoded.usage.llmMs)
        assertEquals(55L, decoded.usage.toolMs)
        assertTrue(decoded.usage.stepCount == 2)
    }

    @Test fun hugeMillisecondValueRoundTripsAsLong() {
        val event = AgentEvent.UsageReceived(round = 1, usage = AgentTokenUsage(llmMs = 5_000_000_000L))
        val decoded = AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)) as AgentEvent.UsageReceived
        assertEquals(5_000_000_000L, decoded.usage.llmMs)
    }

    private fun AgentTokenUsage.hasAnyToken(): Boolean =
        contextTokens != null || inputTokens != null || outputTokens != null
}
