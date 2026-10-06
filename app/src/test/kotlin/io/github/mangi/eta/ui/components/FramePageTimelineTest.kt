package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class FramePageTimelineTest {
    private val chat = FrameDiagnosticPage.Chat
    private val settings = FrameDiagnosticPage.Settings

    @Test fun delayedFrameUsesItsTimestampNotTheNewCurrentPage() {
        val pages = FramePageTimeline()
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        assertEquals(chat, pages.attributeFrame(150, 20).aggregatePage)
        assertEquals(settings, pages.attributeFrame(200, 20).aggregatePage)
    }

    @Test fun frameSpanningRouteChangeIsExplicitlyTransitional() {
        val pages = FramePageTimeline()
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        val frame = pages.attributeFrame(150, 100)
        assertEquals(chat, frame.start)
        assertEquals(settings, frame.end)
        assertTrue(frame.changed)
        assertEquals(FrameDiagnosticPage.Transition, frame.aggregatePage)
        assertTrue(frame.fields().contains("page=Chat pageEnd=Settings pageChanged=true"))
    }

    @Test fun returningToOriginalPageStillCountsAsTransition() {
        val pages = FramePageTimeline()
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        pages.mark(chat, 210)
        val frame = pages.attributeFrame(150, 100)
        assertEquals(chat, frame.start)
        assertEquals(chat, frame.end)
        assertTrue(frame.changed)
        assertEquals(FrameDiagnosticPage.Transition, frame.aggregatePage)
    }

    @Test fun simultaneousChangesRetainTransitionEvidence() {
        val pages = FramePageTimeline()
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        pages.mark(chat, 200)
        assertTrue(pages.attributeFrame(150, 100).changed)
    }

    @Test fun emptyAndEvictedHistoryNeverUseCurrentPageAsFallback() {
        val pages = FramePageTimeline(capacity = 2)
        assertEquals(FrameDiagnosticPage.Unknown, pages.attributeFrame(0, 1).aggregatePage)
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        pages.mark(chat, 300)
        assertEquals(FrameDiagnosticPage.Unknown, pages.attributeFrame(150, 100).aggregatePage)
        assertEquals(settings, pages.attributeFrame(220, 10).aggregatePage)
    }

    @Test fun duplicateUpdatesDoNotEvictUsefulHistory() {
        val pages = FramePageTimeline(capacity = 2)
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        repeat(1000) { pages.mark(settings, 300L + it) }
        assertEquals(chat, pages.attributeFrame(150, 20).aggregatePage)
    }

    @Test fun invalidFrameTimesAreUnknown() {
        val pages = FramePageTimeline()
        pages.mark(settings, 0)
        for ((start, duration) in listOf(-1L to 1L, 10L to -1L, (Long.MAX_VALUE - 2) to 10L)) {
            val frame = pages.attributeFrame(start, duration)
            assertEquals(FrameDiagnosticPage.Unknown, frame.start)
            assertEquals(FrameDiagnosticPage.Unknown, frame.end)
        }
    }

    /** Kept deliberately independent of the indexed implementation: this is the old algorithm. */
    private class ReferenceTimeline(private val capacity: Int) {
        private data class Entry(val atNs: Long, val page: FrameDiagnosticPage)
        private var entries = emptyList<Entry>()

        fun mark(page: FrameDiagnosticPage, nowNs: Long) {
            val last = entries.lastOrNull()
            if (last?.page == page) return
            require(last == null || nowNs >= last.atNs)
            entries = entries.takeLast(capacity - 1) + Entry(nowNs, page)
        }

        fun attribute(startNs: Long, endNs: Long): FramePageAttribution {
            val snapshot = entries
            if (endNs < startNs) return unknown()
            val start = snapshot.lastOrNull { it.atNs <= startNs }?.page ?: FrameDiagnosticPage.Unknown
            val end = snapshot.lastOrNull { it.atNs <= endNs }?.page ?: FrameDiagnosticPage.Unknown
            val changed = snapshot.any { it.atNs > startNs && it.atNs <= endNs }
            return FramePageAttribution(start, end, changed)
        }

        fun attributeFrame(intendedNs: Long, totalNs: Long): FramePageAttribution {
            if (intendedNs < 0 || totalNs < 0 || intendedNs > Long.MAX_VALUE - totalNs) return unknown()
            return attribute(intendedNs, intendedNs + totalNs)
        }

        private fun unknown() = FramePageAttribution(FrameDiagnosticPage.Unknown, FrameDiagnosticPage.Unknown, false)
    }

    private fun assertEquivalent(expected: FramePageAttribution, actual: FramePageAttribution) {
        assertEquals(expected, actual)
        assertEquals(expected.aggregatePage, actual.aggregatePage)
        assertEquals(expected.fields(), actual.fields())
    }

    @Test fun exhaustiveSmallHistoriesMatchOldAlgorithmAtEveryBoundaryAndAfterEviction() {
        val labels = listOf(FrameDiagnosticPage.Unknown, FrameDiagnosticPage.Home, chat)
        val boundaries = listOf(-1L, 0L, 1L, 2L, 3L, 4L)
        for (capacity in listOf(2, 3, 64)) {
            fun visit(history: List<Pair<FrameDiagnosticPage, Long>>, lastTime: Long) {
                val pages = FramePageTimeline(capacity)
                val reference = ReferenceTimeline(capacity)
                history.forEach { (page, time) -> pages.mark(page, time); reference.mark(page, time) }
                for (start in boundaries) for (end in boundaries) {
                    assertEquivalent(reference.attribute(start, end), pages.attribute(start, end))
                }
                if (history.size == 4) return
                for (page in labels) for (time in lastTime..2L) {
                    visit(history + (page to time), time)
                }
            }
            visit(emptyList(), 0)
        }
    }

    @Test fun longHistoriesMatchOldAlgorithmWithDuplicateTimesReturnsAndCapacityTurnover() {
        val random = java.util.Random(0x4652414dL)
        val labels = listOf(FrameDiagnosticPage.Unknown, FrameDiagnosticPage.Home, chat, settings,
            FrameDiagnosticPage.Transition, FrameDiagnosticPage.BrowserOverlay)
        for (capacity in listOf(2, 3, 8, 64)) {
            val pages = FramePageTimeline(capacity)
            val reference = ReferenceTimeline(capacity)
            var now = 0L
            repeat(1000) { index ->
                now += random.nextInt(4)
                val page = labels[random.nextInt(labels.size)]
                pages.mark(page, now)
                reference.mark(page, now)
                val boundaries = listOf(-1L, 0L, now - 1, now, now + 1, Long.MAX_VALUE)
                for (start in boundaries) for (end in boundaries) {
                    assertEquivalent(reference.attribute(start, end), pages.attribute(start, end))
                }
                repeat(8) {
                    val start = random.nextInt((now + 2).toInt()).toLong() - 1
                    val end = random.nextInt((now + 2).toInt()).toLong() - 1
                    assertEquivalent(reference.attribute(start, end), pages.attribute(start, end))
                    assertEquivalent(reference.attributeFrame(start, end - start), pages.attributeFrame(start, end - start))
                }
                if (index % 10 == 0) {
                    // Equal-page updates are ignored before the monotonic-time check in both versions.
                    pages.mark(page, Long.MIN_VALUE)
                    reference.mark(page, Long.MIN_VALUE)
                    assertEquivalent(reference.attribute(now, now), pages.attribute(now, now))
                }
            }
        }
    }

    @Test fun extremeTimestampsAndFrameOverflowChecksMatchOldAlgorithm() {
        val times = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L,
            Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (capacity in listOf(2, 3, 64)) {
            val pages = FramePageTimeline(capacity)
            val reference = ReferenceTimeline(capacity)
            for (time in times) {
                for (page in listOf(FrameDiagnosticPage.Home, chat, FrameDiagnosticPage.Home)) {
                    pages.mark(page, time)
                    reference.mark(page, time)
                    for (start in times) for (end in times) {
                        assertEquivalent(reference.attribute(start, end), pages.attribute(start, end))
                    }
                    for (start in times) for (duration in times) {
                        assertEquivalent(reference.attributeFrame(start, duration), pages.attributeFrame(start, duration))
                    }
                }
            }
        }
    }

    @Test fun capacityAndNonMonotonicMarksKeepTheirValidation() {
        for (capacity in listOf(Int.MIN_VALUE, 0, 1)) {
            try {
                FramePageTimeline(capacity)
                fail("capacity below two must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        val pages = FramePageTimeline(2)
        pages.mark(chat, 10)
        try {
            pages.mark(settings, 9)
            fail("a different page cannot move time backwards")
        } catch (_: IllegalArgumentException) { }
        assertEquals(chat, pages.attribute(10, 10).aggregatePage)
    }

    @Test fun perPageSummariesDoNotRelabelTheWholeWindowOrClearHistory() {
        val pages = FramePageTimeline()
        pages.mark(chat, 100)
        pages.mark(settings, 200)
        val session = StreamPerformanceDiagnostics.Session()
        session.record(pages.attributeFrame(150, 20).aggregatePage.frameStage, 20_000_000, 1)
        session.record(pages.attributeFrame(220, 10).aggregatePage.frameStage, 10_000_000, 0)
        val lines = session.report(final = false)
        assertEquals(2, lines.size)
        assertTrue(lines.all { "scope=window" in it })
        assertTrue(lines.single { "stage=frame.page.Chat " in it }.contains("valueSum=1"))
        assertTrue(lines.single { "stage=frame.page.Settings " in it }.contains("valueSum=0"))
        session.record(pages.attributeFrame(150, 20).aggregatePage.frameStage, 20_000_000, 1)
        assertTrue(session.report(final = false).single().contains("stage=frame.page.Chat "))
    }
}
