package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBodyTraceTest {
    private class RecordingSink : ChatBodyTraceSink {
        val sections = mutableListOf<String>()

        override fun section(name: String) {
            sections += name
        }
    }

    @Test fun disabledGateEmitsNothing() {
        val sink = RecordingSink()
        emitChatBodyTrace("thinking", 7L, enabled = false, sink = sink)
        emitChatBodyTrace("md.phase.success", 7L, enabled = false, sink = sink)
        assertTrue(sink.sections.isEmpty())
    }

    @Test fun enabledGateEmitsOneAnonymousPointMarkerPerCall() {
        val sink = RecordingSink()
        emitChatBodyTrace("md.phase.success", 3L, enabled = true, sink = sink)
        assertEquals(listOf("chat.body.md.phase.success m=3"), sink.sections)
    }

    @Test fun diagnosticCommitCountIsOptInAndUsesOnlyTheRegisteredLiteral() {
        val stages = mutableListOf<String>()
        val sink = ChatBodyCommitSink { stages += it }
        emitChatBodyDiagnosticCommit("md", enabled = false, sink = sink)
        assertTrue(stages.isEmpty())
        emitChatBodyDiagnosticCommit("md", enabled = true, sink = sink)
        emitChatBodyDiagnosticCommit("md.doc", enabled = true, sink = sink)
        emitChatBodyDiagnosticCommit("PRIVATE_PAYLOAD", enabled = true, sink = sink)
        assertEquals(listOf("render.compose", "render.compose"), stages)
        assertTrue(stages.all(StreamDiagnosticLabels::stage))
    }

    @Test fun markerNameCarriesNoContentOrIdentity() {
        val name = chatBodyTraceName("md.doc", 12L)
        assertEquals("chat.body.md.doc m=12", name)
        for (forbidden in listOf("content", "message", "id", "hash", "url", "http")) {
            assertFalse(name, name.contains(forbidden))
        }
    }

    @Test fun mountNumbersAreAnonymousAndMonotonic() {
        val first = nextChatBodyTraceMount()
        val second = nextChatBodyTraceMount()
        assertTrue(second > first)
    }

    @Test fun declaredKindsStayLiteralOnlyAndCoverTheEntryPoints() {
        val literal = Regex("[a-z]+(\\.[a-z]+)*")
        for (kind in CHAT_BODY_TRACE_KINDS) {
            assertTrue("kind=$kind", literal.matches(kind))
        }
        assertEquals(
            setOf("thinking", "tool", "md", "md.doc", "md.phase.loading", "md.phase.error", "md.phase.success"),
            CHAT_BODY_TRACE_KINDS,
        )
    }
}
