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
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    assertNull(local.current())
                    local.with(DiagnosticSpanContext(captured)) { assertEquals(5L, local.capture()!!.sourceSpan) }
                    assertNull(local.current())
                } catch (error: Throwable) { failure.set(error) }
            }
            thread.start(); thread.join(5000)
            assertFalse("worker did not finish", thread.isAlive)
            failure.get()?.let { throw it }
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

    @Test fun frameResidualIsSignedAndGpuAndVsyncAreNotExtraParts() {
        val residual = frame().copy(totalNs = 100, unknownNs = 40, inputNs = 5, animationNs = 5,
            layoutNs = 10, drawNs = 10, syncNs = 5, commandNs = 10, swapNs = 5,
            gpuNs = 99, intendedNs = 1000, vsyncNs = 1020)
        assertEquals(10L, residual.unaccountedNs)
        assertEquals(0L, residual.overlapNs)
        assertEquals(20L, residual.vsyncLateNs)
        val overlapping = residual.copy(totalNs = 80)
        assertEquals(0L, overlapping.unaccountedNs)
        assertEquals(10L, overlapping.overlapNs)
        assertEquals(0L, residual.copy(vsyncNs = 900).vsyncLateNs)
    }

    @Test fun ringOverwriteBudgetAndWindowReset() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2, slowLimit = 2, frameLimit = 1)
        span(ring, 1, 0, 1)
        span(ring, 2, 0, 4_000_000)
        span(ring, 3, 0, 5_000_000)
        span(ring, 4, 0, 6_000_000)
        assertTrue(ring.reserveFrame()); ring.frame(frame())
        assertFalse(ring.reserveFrame(severe = false)) // A severe reservation may evict this light frame.
        val raw = ring.snapshot(10_000_000)
        assertEquals(listOf(2L, 3L, 4L), raw.recentForFrame(frame()).map { it.span })
        val first = raw.select()
        assertEquals(2L, first.overwritten)
        assertEquals(1L, first.slowBudgetDropped)
        assertEquals(1L, first.frameBudgetDropped)
        assertEquals(listOf(2L, 3L), first.spans.map { it.span })
        assertEquals(1L, first.spanOutputTruncated) // fourth slow span still entered the ordinary ring
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

    @Test fun rawSnapshotSelectionDoesNotHoldAdmissionLockOrConsumeLaterRecords() {
        val lock = Any()
        val ring = BoundedDiagnosticDetails(0, admissionLock = lock)
        span(ring, 1, 0, 5_000_000)
        val raw = ring.snapshot(10_000_000)
        span(ring, 2, 10_000_000, 15_000_000)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val selected = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try { assertEquals(1L, raw.select().spans.single().span) }
            catch (error: Throwable) { failure.set(error) }
            finally { selected.countDown() }
        }
        synchronized(lock) {
            worker.start()
            assertTrue("selection must not acquire admission lock", selected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        worker.join(5000)
        failure.get()?.let { throw it }
        assertEquals(2L, ring.drain(20_000_000).spans.single().span)
    }

    @Test fun finalDetailSnapshotRejectsAllLaterSpansAndFrames() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2, frameLimit = 1)
        span(ring, 1, 0, 5_000_000)
        val final = ring.snapshot(10_000_000, final = true).select()
        repeat(1000) { span(ring, 2L + it, 0, 5_000_000) }
        assertFalse(ring.reserveFrame())
        ring.frame(frame())
        val empty = ring.drain(20_000_000)
        assertEquals(1L, final.spans.single().span)
        assertTrue(empty.spans.isEmpty()); assertTrue(empty.frames.isEmpty())
        assertEquals(0L, empty.overwritten)
        assertEquals(1000L, ring.closedRejectedSpans)
        assertEquals(2L, ring.closedRejectedFrames)
    }

    @Test fun fixedContextLabelsDoNotCollapseToUnknown() {
        val labels = ("markdown.annotated.build markdown.annotated.cell markdown.annotated.raw markdown.blockDraw " +
            "markdown.citation.strip markdown.hidden.childHeight markdown.hidden.measure markdown.hidden.reportHeight " +
            "markdown.parse markdown.publish markdown.publishBlock markdown.queueWait markdown.stable.draw markdown.stable.measure " +
            "markdown.tail.draw markdown.tail.measure markdown.targetToPublish reveal.drawContent reveal.graphemes.append " +
            "reveal.graphemes.cacheHit reveal.graphemes.rebuild reveal.layout.update reveal.measure reveal.paths.append " +
            "reveal.paths.cacheHit reveal.paths.nextGrapheme reveal.paths.rebuild reveal.record.cacheHit reveal.record.update " +
            "reveal.saveLayer runtime.checkpoint.buffer.chars runtime.checkpoint.buffer.events runtime.checkpoint.buffer.residency " +
            "runtime.checkpoint.encode runtime.checkpoint.flush.boundary runtime.checkpoint.lockWait runtime.checkpoint.merge " +
            "runtime.checkpoint.write settings.commitTail settings.composition settings.editEntryWait settings.root.draw " +
            "settings.root.measure settings.transform usage.commitTail usage.editEntryWait usage.ledger.encodeEvents " +
            "usage.ledger.serialize usage.ledger.update usage.load.dao.conversations usage.load.dao.liveIds usage.load.dao.messages " +
            "usage.load.dao.perDay usage.load.decode usage.lockWait usage.transform").split(' ')
        assertEquals(56, labels.size)
        for (label in labels) assertEquals(label, StreamDiagnosticLabels.canonicalStage(label))
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
