package io.github.mangi.eta.agent.kimi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KimiSubAgentStatusTest {

    private lateinit var server: KimiTestHttpServer

    @Before
    fun setUp() {
        server = KimiTestHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = KimiWebApiClient(server.origin, "token")

    @Test
    fun mapsSessionStatusAndSummaryIntoOneSnapshot() {
        server.enqueueEnvelope(
            """{"busy":true,"model":"kimi-k2","context_tokens":12345,"max_context_tokens":200000}""",
        )
        server.enqueueEnvelope(
            """{"id":"s-1","workspace_id":"w","last_turn_reason":"failed","agent_config":{"model":"kimi-k2"}}""",
        )

        val status = KimiSubAgentStatusReader.read(client(), "s-1")

        assertNotNull(status)
        requireNotNull(status)
        assertTrue(status.busy)
        assertEquals("kimi-k2", status.model)
        assertEquals(12345, status.contextTokens)
        assertEquals(200000, status.maxContextTokens)
        assertTrue(status.lastTurnFailed)
    }

    @Test
    fun summaryFailureKeepsSessionStatusAndNormalizesZeroToUnknown() {
        server.enqueueEnvelope("""{"busy":false,"model":"kimi-k2","context_tokens":0,"max_context_tokens":0}""")
        server.enqueue(500, """{"code":500,"msg":"boom","data":{}}""")

        val status = KimiSubAgentStatusReader.read(client(), "s-1")

        assertNotNull(status)
        requireNotNull(status)
        assertFalse(status.busy)
        assertEquals("kimi-k2", status.model)
        assertNull(status.contextTokens)
        assertNull(status.maxContextTokens)
        // 摘要读不到时不能把上一轮标成失败。
        assertFalse(status.lastTurnFailed)
    }

    @Test
    fun sessionStatusFailureReturnsNullInsteadOfThrowing() {
        server.enqueue(500, """{"code":500,"msg":"boom","data":{}}""")

        assertNull(KimiSubAgentStatusReader.read(client(), "s-1"))
    }
}
