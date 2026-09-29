package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPerformanceDiagnosticsTest {
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

    @Test fun mainLogSkipsFrameCallbacksAndKeepsSlowOtherMessages() {
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
        assertEquals(listOf("atMs=0 durUs=6000 msg=android.os.Handler/kotlinx.Job"), slow)
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
