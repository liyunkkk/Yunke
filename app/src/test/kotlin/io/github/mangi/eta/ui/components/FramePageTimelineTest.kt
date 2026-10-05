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
