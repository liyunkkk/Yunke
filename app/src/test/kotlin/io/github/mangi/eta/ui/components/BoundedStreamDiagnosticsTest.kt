package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class BoundedStreamDiagnosticsTest {
    @Test fun nestingCaptureAndExceptionRestore() {
        val local = DiagnosticThreadContext()
        val a = StreamDiagnosticAttribution(1, run = 1, conversation = 2, event = 3)
        assertNull(local.current())
        local.with(DiagnosticSpanContext(a, 10)) {
            assertEquals(10L, local.capture()!!.sourceSpan)
            try {
                local.with(DiagnosticSpanContext(a, 11)) {
                    assertEquals(11L, local.current()!!.span)
                    throw IllegalStateException("expected")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(10L, local.current()!!.span)
        }
        assertNull(local.current())
    }

    @Test fun captureIsNotImplicitlyInstalledOnOtherThread() {
        val local = DiagnosticThreadContext()
        val a = StreamDiagnosticAttribution(1, event = 2)
        local.with(DiagnosticSpanContext(a, 5)) {
            val captured = local.capture()
            val thread = Thread {
                assertNull(local.current())
                local.with(DiagnosticSpanContext(captured)) { assertEquals(5L, local.capture()!!.sourceSpan) }
                assertNull(local.current())
            }
            thread.start(); thread.join()
        }
    }

    @Test fun tokensStableAnonymousAndBounded() {
        val tokens = AnonymousDiagnosticTokens(2)
        assertEquals(0, tokens.token(null))
        assertEquals(1, tokens.token("private-run"))
        assertEquals(1, tokens.token("private-run"))
        assertEquals(2, tokens.token("private-conversation"))
        assertEquals(0, tokens.token("overflow"))
        assertEquals(1L, tokens.saturated)
        assertEquals(1, tokens.token("private-run"))
        assertFalse(StreamDiagnosticAttribution(1, run = 1).toString().contains("private-run"))
    }

    private fun span(ring: BoundedDiagnosticDetails, sequence: Long, begin: Long, end: Long, main: Boolean = true) {
        ring.span("ui.messages.transform", sequence, 0, begin, end, 1, main,
            StreamDiagnosticAttribution(1, run = 1, conversation = 2), 0, 0, value = 42)
    }
    private fun frame(intended: Long = 0) = DiagnosticFrameRecord(intended, intended, 10_000_000, 8_333_333,
        0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test fun ringOverwriteBudgetAndWindowReset() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2, slowLimit = 2, frameLimit = 1)
        span(ring, 1, 0, 1)
        span(ring, 2, 0, 4_000_000)
        span(ring, 3, 0, 5_000_000)
        span(ring, 4, 0, 6_000_000)
        assertTrue(ring.reserveFrame()); ring.frame(frame())
        assertFalse(ring.reserveFrame())
        val first = ring.drain(10_000_000)
        assertEquals(1L, first.overwritten)
        assertEquals(1L, first.slowBudgetDropped)
        assertEquals(1L, first.frameBudgetDropped)
        assertEquals(listOf(2L, 3L), first.spans.map { it.span })
        assertTrue(first.spans.all { it.value == 42L })
        assertTrue(first.frames.single().missed) // <33ms is still abnormal.
        assertEquals(0L, first.fromNs); assertEquals(10_000_000L, first.toNs)
        span(ring, 5, 10_000_000, 14_000_000)
        val next = ring.drain(15_000_000)
        assertEquals(10_000_000L, next.fromNs)
        assertEquals(0L, next.overwritten); assertEquals(0L, next.slowBudgetDropped)
        assertEquals(5L, next.spans.single().span)
    }

    @Test fun frameOverlapUsesHalfOpenIntervalsAndCountsOutputTruncation() {
        val ring = BoundedDiagnosticDetails(0, capacity = 8, slowLimit = 1)
        span(ring, 1, 0, 1) // overlaps frame at 0
        span(ring, 2, 1, 2) // overlaps, but output budget is exhausted
        span(ring, 3, 10_000_000, 10_000_001) // exact end boundary does not overlap
        ring.frame(frame())
        val snapshot = ring.drain(11_000_000)
        assertEquals(listOf(1L), snapshot.spans.map { it.span })
        assertEquals(1L, snapshot.spanOutputTruncated)
    }

    @Test fun labelsNeverRetainUnknownSuffixOrPayload() {
        assertEquals("markdown.parse", StreamDiagnosticLabels.canonicalStage("markdown.parse"))
        assertEquals("settings.unknown", StreamDiagnosticLabels.canonicalStage("settings.private-user-id"))
        assertEquals("runtime.checkpoint.unknown", StreamDiagnosticLabels.canonicalStage("runtime.checkpoint.payload"))
        assertNull(StreamDiagnosticLabels.canonicalStage("private-text"))
        assertEquals("unknown", StreamDiagnosticLabels.kind("private-kind"))
    }

    @Test fun eventLinksUseIdentityAndAreBounded() {
        val links = DiagnosticEventLinks(1)
        val a = Any(); val b = Any()
        val attribution = StreamDiagnosticAttribution(1, event = 2)
        links.bind(a, attribution)
        links.bind(a, attribution.copy(event = 3))
        assertEquals(3L, links.find(a)!!.event)
        links.bind(b, attribution)
        assertNull(links.find(a)); assertEquals(2L, links.find(b)!!.event)
        assertTrue(links.overwritten >= 1)
    }
}
