package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPerformanceDiagnosticsTest {
    @Test fun stageLimitKeepsKnownCountersAndReportsOverflowWithoutPayloadNames() {
        val session = StreamPerformanceDiagnostics.Session()
        repeat(STREAM_DIAGNOSTIC_STAGE_LIMIT) { session.record("test.$it", 1000, 1) }
        repeat(3) { session.record("not-retained-secret", 1000, 1) }
        session.record("test.0", 2000, 2)
        val lines = session.report(final = false)
        assertEquals(STREAM_DIAGNOSTIC_STAGE_LIMIT + 1, lines.size)
        assertTrue(lines.single { "stage=test.0 " in it }.contains("n=2"))
        assertTrue(lines.last().contains("stage=diagnostic.stageOverflow droppedRecords=3"))
        assertTrue(lines.none { "not-retained-secret" in it })
        session.record("next.window", 1000, 0)
        assertTrue(session.report(final = false).single().contains("stage=next.window"))
    }

    @Test fun derivedDeltaGapRespectsStageLimitAndClosedSessionStaysClosed() {
        val session = StreamPerformanceDiagnostics.Session()
        repeat(STREAM_DIAGNOSTIC_STAGE_LIMIT - 1) { session.record("test.$it", 1, 0) }
        session.record("ui.delta.received", 0, 1)
        session.record("ui.delta.received", 0, 1)
        val lines = session.report(final = true)
        assertEquals(STREAM_DIAGNOSTIC_STAGE_LIMIT + 1, lines.size)
        assertTrue(lines.single { "stage=ui.delta.received " in it }.contains("n=2"))
        assertTrue(lines.last().contains("droppedRecords=1"))
        session.record("after.close", 1, 0)
        assertTrue(session.report(final = true).single().contains("empty=1"))
    }

    @Test fun admissionCutoffIsAtomicAndLateTimestampBelongsToNextSnapshot() {
        val cutoffEntered = java.util.concurrent.CountDownLatch(1)
        val releaseCutoff = java.util.concurrent.CountDownLatch(1)
        val admissionAttempted = java.util.concurrent.CountDownLatch(1)
        val admitted = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val captured = java.util.concurrent.atomic.AtomicReference<StreamPerformanceDiagnostics.SessionSnapshot?>()
        val session = StreamPerformanceDiagnostics.Session {
            when (calls.getAndIncrement()) {
                0 -> 0L
                1 -> {
                    cutoffEntered.countDown()
                    check(releaseCutoff.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    20_000_000L
                }
                else -> 40_000_000L
            }
        }
        val span = session.beginSpan()!!
        val snapshotThread = Thread {
            try { captured.set(session.snapshot(false)) } catch (error: Throwable) { failure.set(error) }
        }
        val admissionThread = Thread {
            try {
                admissionAttempted.countDown()
                session.finishSpan("markdown.parse", span, 0, 0, 10_000_000, 1, true, null, 0, 0, 0, 7)
            } catch (error: Throwable) { failure.set(error) } finally { admitted.countDown() }
        }
        snapshotThread.start()
        try {
            assertTrue(cutoffEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            admissionThread.start()
            assertTrue(admissionAttempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, admitted.count) // Snapshot owns the same lock as aggregate + detail admission.
        } finally {
            releaseCutoff.countDown()
            snapshotThread.join(5000)
            if (admissionThread.state != Thread.State.NEW) admissionThread.join(5000)
        }
        assertFalse(snapshotThread.isAlive); assertFalse(admissionThread.isAlive)
        failure.get()?.let { throw it }
        val first = captured.get()!!
        assertTrue(first.stats.isEmpty()); assertTrue(first.rawDetails.select().spans.isEmpty())
        assertEquals(1L, first.openSpans)
        val next = session.snapshot(false)
        assertEquals(20_000_000L, next.rawDetails.fromNs)
        assertEquals(1L, next.stats.getValue("markdown.parse").count)
        assertEquals(span, next.rawDetails.select().spans.single().span)
        assertTrue(next.rawDetails.select().spans.single().endNs < next.rawDetails.fromNs)
        assertEquals(0L, next.openSpans)
        assertTrue(next.report("golden", 0, false).all { "boundary=admissionSnapshot" in it })
        assertFalse(next.report("golden", 0, false).any { "halfOpenCompletion" in it })
        session.record("markdown.parse", 1, 0)
        assertEquals(1L, next.stats.getValue("markdown.parse").count) // Detached aggregates cannot be mutated.
    }

    @Test fun finalClosesAdmissionAndLateSpansCannotLeaveDetailResidue() {
        var now = 0L
        val session = StreamPerformanceDiagnostics.Session { now }
        val span = session.beginSpan()!!
        now = 20_000_000L
        val final = session.snapshot(true)
        assertTrue(session.closed); assertTrue(session.details.closed)
        assertEquals(1L, final.openSpans)
        session.finishSpan("markdown.parse", span, 0, 0, 30_000_000, 1, true, null, 0, 0, 0, 0)
        repeat(1000) { session.record("markdown.parse", 1, 0) }
        assertNull(session.beginSpan()); assertNull(session.reserveNote())
        assertEquals(1002L to 1L, session.closedCounts())
        now = 40_000_000L
        val after = session.snapshot(true)
        assertTrue(after.stats.isEmpty()); assertTrue(after.pages.isEmpty())
        assertTrue(after.rawDetails.select().spans.isEmpty()); assertTrue(after.rawDetails.select().frames.isEmpty())
        assertEquals(0L, after.openSpans); assertEquals(1L, after.lateSpans)
        // The first final snapshot intentionally does not claim observation of later completions.
        assertEquals(0L, final.lateSpans)
    }

    @Test fun noteBudgetIsReservedBeforeLazyDetailAndResetsOnlyWithNewSession() {
        val session = StreamPerformanceDiagnostics.Session()
        var evaluations = 0
        val detail = { evaluations++; "fixed=1" }
        repeat(NOTE_MAX_PER_SESSION + 5) {
            if (session.reserveNote() != null) detail()
        }
        assertEquals(NOTE_MAX_PER_SESSION, evaluations)
        assertEquals(5L, session.snapshot(false).noteDropped)
        assertNull(session.reserveNote())
        assertEquals(6L, session.snapshot(false).noteDropped)
        assertEquals(1, StreamPerformanceDiagnostics.Session().reserveNote())
    }

    @Test fun versionNameIsNumericDottedOrUnknown() {
        for (version in listOf("1.2", "1.23.456.7890", "0001.0002")) assertEquals(version, diagnosticVersionName(version))
        for (version in listOf(null, "1", "1.2.3.4.5", "12345.2", "1.2-beta", "deadbeef", "１.２", "1.2 private")) {
            assertEquals("unknown", diagnosticVersionName(version))
        }
    }

    @Test fun unaccountedIsTotalMinusNonOverlappingParts() {
        assertEquals(10L, frameUnaccountedNs(
            total = 100, unknown = 40, input = 5, animation = 5,
            layout = 10, draw = 10, sync = 10, command = 5, swap = 5,
        ))
    }

    @Test fun gpuIsNotSubtractedFromTheTotal() {
        assertEquals(0L, frameUnaccountedNs(
            total = 80, unknown = 80, input = 0, animation = 0,
            layout = 0, draw = 0, sync = 0, command = 0, swap = 0,
        ))
    }

    @Test fun overlapIsNegativeWhenPartsExceedTotal() {
        assertEquals(-3L, frameUnaccountedNs(
            total = 7, unknown = 4, input = 3, animation = 3,
            layout = 0, draw = 0, sync = 0, command = 0, swap = 0,
        ))
    }

    @Test fun looperLineKeepsOnlyHandlerAndCallbackClass() {
        assertEquals(
            "android.view.Choreographer\$FrameHandler/android.view.Choreographer\$FrameDisplayEventReceiver",
            toggleProbeMessageName(
                ">>>>> Dispatching to Handler (android.view.Choreographer\$FrameHandler) {9f1c2d3} " +
                    "android.view.Choreographer\$FrameDisplayEventReceiver@4a5b6c7: 0",
            ),
        )
    }

    @Test fun looperLineWithoutCallbackStaysBounded() {
        val name = toggleProbeMessageName(
            ">>>>> Dispatching to Handler (android.app.ActivityThread\$H) {1a2b3c} null: 159",
        )
        assertEquals("android.app.ActivityThread\$H/null", name)
        assertTrue(name.length <= 160)
    }

    private fun probe() = ToggleProbe(kind = "tool", expanded = true, token = 1, generation = 1, startNs = 0L)

    @Test fun toggleProbeClosesAfterFixedFrameWindow() {
        val probe = probe()
        repeat(TOGGLE_PROBE_FRAMES - 1) { assertFalse(probe.addFrame("f")) }
        assertTrue(probe.addFrame("f"))
        assertFalse(probe.expired(TOGGLE_PROBE_MAX_NS - 1))
        assertTrue(probe.expired(TOGGLE_PROBE_MAX_NS))
    }

    @Test fun toggleProbeWindowCoversExpandAnimationAndFollowCatchUp() {
        // 180ms 展开动画加跟底追赶；120Hz 下至少要盖住 1 秒。
        assertTrue(TOGGLE_PROBE_FRAMES >= 120)
        assertTrue(TOGGLE_PROBE_MAX_NS >= 1_000_000_000L)
    }

    @Test fun unchangedListSampleIsNotRepeated() {
        val probe = probe()
        assertTrue(probe.addSample(1_000_000, "follow=true lift=false"))
        assertFalse(probe.addSample(2_000_000, "follow=true lift=false"))
        assertTrue(probe.addSample(3_000_000, "follow=true lift=true"))
        val lines = probe.report("s", emptyList())
        assertEquals(2, lines.count { it.contains("tag=list") })
    }

    @Test fun eventsAreCappedAndCounted() {
        val probe = probe()
        repeat(TOGGLE_PROBE_MAX_EVENTS + 5) { probe.addEvent(it.toLong(), "measure", "h=$it") }
        assertEquals(5, probe.droppedEvents)
        val header = probe.report("s", emptyList()).first()
        assertTrue(header.contains("events=$TOGGLE_PROBE_MAX_EVENTS"))
        assertTrue(header.contains("droppedEvents=5"))
    }

    @Test fun eventTimeIsRelativeToTapWithTenthsOfAMillisecond() {
        val probe = ToggleProbe("thinking", true, token = 3, generation = 2, startNs = 10_000_000L)
        probe.addEvent(12_340_000L, "draw", "part=visible")
        val line = probe.report("s", emptyList()).single { it.contains("tag=draw") }
        assertTrue(line, line.contains("sinceTapMs=2.3"))
        assertTrue(line.contains("gen=2"))
    }

    @Test fun headerSummarisesWorstFrameAndMisses() {
        val probe = probe()
        probe.addFrame("a", totalUs = 9_000, missed = false)
        probe.addFrame("b", totalUs = 31_000, missed = true)
        val header = probe.report("s", listOf("atMs=1 durUs=5000 msg=x/y")).first()
        assertTrue(header.contains("maxFrameUs=31000"))
        assertTrue(header.contains("missed=1"))
        assertTrue(header.contains("slowMessages=1"))
    }

    @Test fun mainLogIncludesFrameCallbacksAndKeepsSlowOtherMessages() {
        val log = MainThreadMessageLog(capacity = 4)
        val frame = ">>>>> Dispatching to Handler (android.view.Choreographer\$FrameHandler) {1} " +
            "android.view.Choreographer\$FrameDisplayEventReceiver@2: 0"
        log.onLine(frame, 0)
        log.onLine("<<<<< Finished to Handler (android.view.Choreographer\$FrameHandler) {1} x", 20_000_000)
        log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {3} kotlinx.Job@4: 0", 30_000_000)
        log.onLine("<<<<< Finished to Handler (android.os.Handler) {3} x", 36_000_000)
        log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {3} Fast@5: 0", 40_000_000)
        log.onLine("<<<<< Finished to Handler (android.os.Handler) {3} x", 41_000_000)
        assertEquals(1L, log.frameMessages)
        assertEquals(2L, log.otherMessages)
        val slow = log.between(0, 100_000_000, originNs = 30_000_000, limit = 10)
        assertEquals(2, slow.size)
        assertTrue(slow.first().contains("Choreographer"))
        assertEquals("atMs=0 durUs=6000 msg=android.os.Handler/kotlinx.Job", slow.last())
    }

    @Test fun slowMessageReportsTimeCoveredByMeasuredStages() {
        val log = MainThreadMessageLog(capacity = 4)
        log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {3} q60@4: 0", 0)
        log.addCovered("ui.flush", 12_000_000)
        log.addCovered("reveal.step", 3_000_000)
        log.onLine("<<<<< Finished to Handler (android.os.Handler) {3} x", 30_000_000)
        val line = log.between(0, 100_000_000, originNs = 0, limit = 10).single()
        assertTrue(line, line.endsWith("coveredUs=15000 top=ui.flush:12000"))
        // 消息之外的计时不计入。
        log.addCovered("ui.flush", 5_000_000)
        assertEquals(1, log.between(0, Long.MAX_VALUE, 0, 10).size)
    }

    @Test fun numericDispatchAccountingKeepsNestedRevealAsASubsetAndDoesNotLeakNames() {
        var callback: List<Long>? = null
        val log = MainThreadMessageLog(capacity = 4, onMessage = { begin, end, _, covered, reveal ->
            callback = listOf(begin, end, covered, reveal)
        })
        log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {3} PRIVATE_PAYLOAD@4: 0", 0)
        // The collector receives only outermost scopes, so a child reveal is not covered twice.
        log.addCovered("ui.flush", 12_000_000)
        log.addReveal(3_000_000)
        log.onLine("<<<<< Finished", 30_000_000)
        assertEquals(listOf(0L, 30_000_000L, 12_000_000L, 3_000_000L), callback)
        val sample = log.timingsBetween(0, 30_000_000).single()
        assertEquals(18_000_000L, sample.uninstrumentedNs)
        assertEquals(27_000_000L, sample.nonRevealNs)
        assertFalse(sample.frameDispatch)
        assertFalse(sample.toString().contains("PRIVATE_PAYLOAD"))
        assertTrue(log.timingsBetween(30_000_000, 40_000_000).isEmpty())
    }

    @Test fun revealAndCoveredCountersResetAndAreClampedToDispatchWallTime() {
        val log = MainThreadMessageLog(capacity = 2)
        log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {3} x@4: 0", 0)
        log.addCovered("ui.flush", 30_000_000)
        log.addReveal(50_000_000)
        log.onLine("<<<<< Finished", 10_000_000)
        log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {3} x@4: 0", 20_000_000)
        log.onLine("<<<<< Finished", 30_000_000)
        val samples = log.timingsBetween(0, 40_000_000)
        assertEquals(0L, samples[0].uninstrumentedNs)
        assertEquals(0L, samples[0].nonRevealNs)
        assertEquals(10_000_000L, samples[1].uninstrumentedNs)
        assertEquals(10_000_000L, samples[1].nonRevealNs)
    }

    @Test fun disabledMeasureRunsTheOriginalBlockExactlyOnceWithoutAnyRecord() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        var calls = 0
        val result = StreamPerformanceDiagnostics.measure("main.uninstrumented") { ++calls; "result" }
        assertEquals("result", result)
        assertEquals(1, calls)
    }

    @Test fun supplementalStageRegistryContainsOnlyFixedShortLiterals() {
        assertEquals(setOf("main.uninstrumented", "main.nonReveal", "chat.content.commit", "list.measure", "list.place", "row.measure", "row.place", "row.draw", "settings.section.measure", "settings.section.draw"),
            StreamDiagnosticGapLabels.stages)
        for (stage in StreamDiagnosticGapLabels.stages) {
            assertTrue(Regex("[a-z]+(?:\\.[a-zA-Z]+)+").matches(stage))
            assertEquals(stage, StreamDiagnosticLabels.canonicalStage(stage))
        }
        assertNull(StreamDiagnosticLabels.canonicalStage("list.PRIVATE_PAYLOAD"))
        assertNull(StreamDiagnosticLabels.canonicalStage("main.PRIVATE_PAYLOAD"))
    }

    @Test fun mainLogIgnoresMessageThatStartedBeforePrinterWasInstalled() {
        val log = MainThreadMessageLog(capacity = 4)
        log.onLine("<<<<< Finished to Handler (android.os.Handler) {3} x", 50_000_000)
        assertEquals(0L, log.otherMessages)
        assertTrue(log.between(0, Long.MAX_VALUE, 0, 10).isEmpty())
    }

    @Test fun mainLogRingBufferKeepsNewestAndHonoursRangeAndLimit() {
        val log = MainThreadMessageLog(capacity = 3)
        repeat(5) { i ->
            val start = i * 100_000_000L
            log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {1} Job$i@1: 0", start)
            log.onLine("<<<<< Finished", start + 10_000_000)
        }
        val all = log.between(0, Long.MAX_VALUE, 0, 10)
        assertEquals(3, all.size)
        assertTrue(all.first().contains("Job2"))
        assertTrue(all.last().contains("Job4"))
        assertEquals(1, log.between(0, Long.MAX_VALUE, 0, 1).size)
        val ranged = log.between(305_000_000, 305_000_000, 0, 10)
        assertEquals(1, ranged.size)
        assertTrue(ranged.single().contains("Job3"))
    }

    @Test fun customRunnableToStringCannotLeakFieldValues() {
        val name = toggleProbeMessageName(
            ">>>>> Dispatching to Handler (android.os.Handler) {1a2b} Job(text=secret message, id=42): 0",
        )
        assertEquals("android.os.Handler/Job", name)
    }
}
