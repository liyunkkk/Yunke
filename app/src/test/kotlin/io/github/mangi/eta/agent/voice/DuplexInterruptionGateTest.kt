package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class DuplexInterruptionGateTest {
    @Test fun speculativeStartsAndEmptyTranscriptsKeepQueuedReply() {
        val gate = DuplexInterruptionGate()
        var queuedBytes = 356196
        var reply = "未播完的回答"
        val interrupt = { queuedBytes = 0; reply = "" }
        repeat(3) {
            gate.started()
            for (text in listOf("", "   ", "。", "…")) {
                assertNull(gate.transcript(text, false, interrupt))
            }
            assertNull(gate.transcript("", true, interrupt))
        }
        assertEquals(356196, queuedBytes)
        assertEquals("未播完的回答", reply)
    }

    @Test fun realShortSpeechInterruptsOncePerTurn() {
        val gate = DuplexInterruptionGate()
        var interrupts = 0
        gate.started()
        assertEquals("停", gate.transcript("停", false) { interrupts++ })
        assertEquals("停一下", gate.transcript("停一下", false) { interrupts++ })
        assertEquals("停一下。", gate.transcript("停一下。", true) { interrupts++ })
        assertEquals(1, interrupts)
        gate.started()
        assertEquals("好", gate.transcript("好", true) { interrupts++ })
        assertEquals(2, interrupts)
    }

    @Test fun completionWithoutDeltasWorksAndEmptyCompletionKeepsCurrentHypothesis() {
        val gate = DuplexInterruptionGate()
        var interrupts = 0
        assertEquals("你好", gate.transcript("你好", true) { interrupts++ })
        gate.started()
        assertNull(gate.transcript("", true) { interrupts++ })
        assertEquals(1, interrupts)
        gate.started()
        gate.transcript("等一下", false) { interrupts++ }
        assertEquals("等一下", gate.transcript("", true) { interrupts++ })
        assertEquals(2, interrupts)
    }

    @Test fun nullOrStructuredPayloadIsNotRecognizedSpeech() {
        val event = JSONObject().put("text", JSONObject.NULL).put("delta", JSONObject())
        assertEquals("", DoubaoDuplexProtocol.eventText(event))
        event.put("transcript", "停")
        assertEquals("停", DoubaoDuplexProtocol.eventText(event))
    }
}
