package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class FrameSpanCorrelationTest {
    private fun frame(from: Long = 100, total: Long = 100) = DiagnosticFrameRecord(from, from, total, 80,
        0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private fun span(id: Long, from: Long, to: Long, parent: Long = 0, main: Boolean = true,
        attr: StreamDiagnosticAttribution? = null) = DiagnosticSpanRecord("list.measure", id, parent, from, to,
        if (main) 1 else 2, main, attr, 0, 0, 0)
    private fun add(ring: BoundedDiagnosticDetails, span: DiagnosticSpanRecord) = with(span) {
        ring.span(stage, this.span, parent, beginNs, endNs, thread, main, attribution, page, pageEnd, value)
    }
    private fun listFrame() = frame(500_000_000, 24_000_000).copy(page = 5, pageEnd = 5, pageSegment = 7)
    private fun listSamples(target: DiagnosticFrameRecord, vararg lists: Long) = DiagnosticListSamples().also { samples ->
        lists.forEach { list ->
            samples.add(DiagnosticListSnapshot(target.intendedNs + 10, list, 1, 1, 0, 0, 0, 100, 1,
                emptyList(), target.page, target.pageSegment))
        }
    }
    private fun addCapturePrefix(ring: BoundedDiagnosticDetails, target: DiagnosticFrameRecord) {
        val attr = StreamDiagnosticAttribution(1, list = 11)
        add(ring, span(1, target.intendedNs + 1, target.intendedNs + 2_000_001, attr = attr))
        repeat(FRAME_CAPTURE_MAX_SPANS - 1) {
            add(ring, span(2L + it, target.intendedNs + 1, target.intendedNs + 2_000_001)
                .copy(stage = "row.measure"))
        }
    }

    @Test fun halfOpenIntervalsExcludeTouchesAndLookbackIsNotFrameOverlap() {
        val result = correlateDiagnosticFrame(frame(), listOf(span(1, 90, 100), span(2, 200, 210),
            span(3, 100, 101), span(4, 120, 130, main = false)))
        assertEquals(listOf(3L), result.overlaps.map { it.span })
        assertEquals(listOf(1L), result.preceding.map { it.span })
        assertEquals(1L, result.overlapUnionNs)
        assertEquals(99L, result.frameWallOutsideSpansNs)
    }

    @Test fun ninePrecedingMainSpansReportTheOneLookbackOmission() {
        val from = 500_000_000L
        val preceding = (1L..9L).map { span(it, from - it * 10_000_000, from - it * 10_000_000 + 1) }
        val result = correlateDiagnosticFrame(frame(from, 24_000_000), preceding +
            span(10, from - FRAME_CORRELATION_LOOKBACK_NS - 1, from - FRAME_CORRELATION_LOOKBACK_NS))
        assertEquals(0, result.totalOverlaps)
        assertEquals(9, result.totalPreceding)
        assertEquals((1L..8L).toList(), result.preceding.map { it.span })
        assertEquals(1, result.precedingOmitted)
    }

    @Test fun lateNonSevereDeadlineMissRetainsSubFourMsEvidenceAcrossSnapshot() {
        val ring = BoundedDiagnosticDetails(0)
        add(ring, span(1, 4_991_000_000, 4_993_000_000))
        assertTrue(ring.snapshot(5_000_000_000).select().spans.isEmpty())
        val delayed = frame(4_990_000_000, 24_000_000).copy(deadlineNs = 16_000_000)
        assertTrue(delayed.missed); assertFalse(delayed.severe); assertEquals(0L, delayed.unknownNs)
        assertTrue(ring.reserveFrame(severe = false))
        val evidence = ring.protectFrame(delayed)
        ring.frame(delayed.copy(sourceWindowLoss = evidence.sourceWindowLoss,
            sourceWindowUnknown = evidence.sourceWindowUnknown))
        val detail = ring.drain(10_000_000_000)
        assertEquals(listOf(1L), evidence.spans.map { it.span })
        assertEquals(1L, detail.previousWindowSpans)
        assertEquals(1, correlateDiagnosticFrame(detail.frames.single(), detail.spans).totalOverlaps)
        assertFalse(diagnosticFrameEvidenceIncomplete(detail.frames.single(), detail, openSpans = 0))
    }

    @Test fun overwrittenPreviousWindowCannotClaimCompleteDelayedSevereFrameEvidence() {
        val ring = BoundedDiagnosticDetails(0, capacity = 1)
        add(ring, span(1, 4_991_000_000, 4_993_000_000))
        add(ring, span(2, 4_994_000_000, 4_995_000_000, main = false))
        assertEquals(1L, ring.snapshot(5_000_000_000).select().overwritten)
        val delayed = frame(4_990_000_000, 40_000_000).copy(deadlineNs = 16_000_000)
        assertTrue(delayed.severe); assertTrue(ring.reserveFrame(severe = true))
        val evidence = ring.protectFrame(delayed)
        ring.frame(delayed.copy(sourceWindowLoss = evidence.sourceWindowLoss,
            sourceWindowUnknown = evidence.sourceWindowUnknown))
        val detail = ring.drain(10_000_000_000)
        assertEquals(0L, detail.overwritten) // The loss was in the previous admission window.
        assertEquals(0, correlateDiagnosticFrame(detail.frames.single(), detail.spans).totalOverlaps)
        assertTrue(detail.frames.single().sourceWindowLoss)
        assertTrue(diagnosticFrameEvidenceIncomplete(detail.frames.single(), detail, openSpans = 0))
    }

    @Test fun previousAdmissionLossIsNotHiddenByLateCompletionTimestampsOutsideItsNominalWindow() {
        val ring = BoundedDiagnosticDetails(0, capacity = 1)
        ring.snapshot(5_000_000_000)
        add(ring, span(1, 4_991_000_000, 4_993_000_000)) // Late admission belongs to [5s, 10s).
        add(ring, span(2, 5_100_000_000, 5_100_000_001, main = false))
        ring.snapshot(10_000_000_000)
        val evidence = ring.protectFrame(frame(4_990_000_000, 40_000_000))
        assertTrue(evidence.sourceWindowLoss)
        assertTrue(evidence.sourceWindowUnknown)
    }

    @Test fun frameOlderThanTheSingleRetainedGenerationIsExplicitlyUnknownButFreshFramesAreNot() {
        val ring = BoundedDiagnosticDetails(0)
        add(ring, span(1, 4_991_000_000, 4_993_000_000))
        ring.snapshot(5_000_000_000); ring.snapshot(10_000_000_000)
        val old = frame(4_990_000_000, 24_000_000)
        val oldEvidence = ring.protectFrame(old)
        assertTrue(oldEvidence.sourceWindowUnknown)
        ring.frame(old.copy(sourceWindowLoss = oldEvidence.sourceWindowLoss,
            sourceWindowUnknown = oldEvidence.sourceWindowUnknown))
        val oldDetail = ring.drain(15_000_000_000)
        assertEquals(0, correlateDiagnosticFrame(oldDetail.frames.single(), oldDetail.spans).totalOverlaps)
        assertTrue(diagnosticFrameEvidenceIncomplete(oldDetail.frames.single(), oldDetail, openSpans = 0))
        val fresh = frame(15_500_000_000, 24_000_000)
        val evidence = ring.protectFrame(fresh)
        assertFalse(evidence.sourceWindowUnknown); assertFalse(evidence.sourceWindowLoss)
        ring.frame(fresh.copy(sourceWindowLoss = evidence.sourceWindowLoss,
            sourceWindowUnknown = evidence.sourceWindowUnknown))
        val detail = ring.drain(20_000_000_000)
        assertFalse(diagnosticFrameEvidenceIncomplete(detail.frames.single(), detail, openSpans = 0))
    }

    @Test fun oneBoundedCaptureIsReusedForChatGeometryAndShortSpanRetention() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2)
        val attr = StreamDiagnosticAttribution(1, list = 11)
        add(ring, span(1, 500_000_001, 500_000_002, attr = attr))
        val target = frame(500_000_000, 24_000_000).copy(page = 5, pageEnd = 5, pageSegment = 7)
        val samples = DiagnosticListSamples()
        samples.add(DiagnosticListSnapshot(500_000_010, 11, 1, 1, 0, 0, 0, 100, 1, emptyList(), 5, 7))
        samples.add(DiagnosticListSnapshot(500_000_010, 22, 1, 1, 0, 0, 0, 100, 1, emptyList(), 5, 7))
        var clock = 10L
        val costs = DiagnosticObserverCosts { clock }
        val captured = costs.observe(DiagnosticObserverCosts.Phase.Protect) {
            val evidence = ring.protectFrame(target)
            repeat(4) { add(ring, span(2L + it, 600_000_000L + it, 600_000_001L + it)) }
            clock += 20
            val geometry = samples.forFrame(target, evidence)
            clock += 7
            target.copy(listSnapshot = geometry, sourceWindowLoss = evidence.sourceWindowLoss,
                sourceWindowUnknown = evidence.sourceWindowUnknown)
        }
        ring.frame(captured)
        val detail = ring.drain(1_000_000_000)
        assertEquals(11L, detail.frames.single().listSnapshot!!.list)
        assertEquals(listOf(1L), detail.spans.map { it.span })
        val cost = costs.summary().single { "observerPhase=Protect " in it }
        assertTrue(cost.contains("count=1 ")); assertTrue(cost.contains("totalNs=27 "))
        assertTrue(cost.contains("recursiveMeasurement=false"))
    }

    @Test fun secondListSourceBeyond512ProtectedSpansCannotBecomeAUniqueSource() {
        val target = listFrame()
        val ring = BoundedDiagnosticDetails(0, capacity = FRAME_CAPTURE_MAX_SPANS + 1)
        addCapturePrefix(ring, target)
        val secondId = FRAME_CAPTURE_MAX_SPANS.toLong() + 1
        add(ring, span(secondId, target.intendedNs + 3_000_000, target.intendedNs + 3_500_000,
            attr = StreamDiagnosticAttribution(1, list = 22)).copy(stage = "list.place"))
        assertTrue(ring.reserveFrame(severe = false))
        val evidence = ring.protectFrame(target)
        assertFalse(evidence.sourceWindowLoss); assertFalse(evidence.sourceWindowUnknown)
        assertEquals(513, evidence.spans.size)
        assertEquals(secondId, evidence.spans.last().span) // Smaller overlap puts this source outside take(512).
        val samples = listSamples(target, 11) // Missing list22 geometry must not make list11 unique either.
        val geometry = samples.forFrame(target, evidence)
        assertNull(geometry)
        ring.frame(target.copy(listSnapshot = geometry))
        val detail = ring.drain(1_000_000_000)
        assertEquals(1L, detail.frameCaptureTruncated)
        assertEquals(FRAME_CAPTURE_MAX_SPANS, detail.spans.size)
        assertFalse(detail.spans.any { it.span == secondId })
        assertNull(detail.frames.single().listSnapshot)
    }

    @Test fun missingAnonymousListTokenBeyond512ProtectedSpansFailsClosed() {
        val target = listFrame()
        for (missing in listOf<StreamDiagnosticAttribution?>(null, StreamDiagnosticAttribution(1, list = 0))) {
            val ring = BoundedDiagnosticDetails(0, capacity = FRAME_CAPTURE_MAX_SPANS + 1)
            addCapturePrefix(ring, target)
            add(ring, span(FRAME_CAPTURE_MAX_SPANS.toLong() + 1,
                target.intendedNs + 3_000_000, target.intendedNs + 3_500_000, attr = missing)
                .copy(stage = "list.place"))
            assertTrue(ring.reserveFrame(severe = false))
            val evidence = ring.protectFrame(target)
            assertFalse(evidence.sourceWindowLoss); assertFalse(evidence.sourceWindowUnknown)
            assertEquals(513, evidence.spans.size)
            assertEquals(0L, evidence.spans.last().attribution?.list ?: 0L)
            assertNull(listSamples(target, 11).forFrame(target, evidence))
            assertEquals(1L, ring.drain(1_000_000_000).frameCaptureTruncated)
        }
    }

    @Test fun untruncatedUniqueListSourceStillMatchesAmongMultipleGeometrySources() {
        val target = listFrame()
        val ring = BoundedDiagnosticDetails(0, capacity = 2)
        val attr = StreamDiagnosticAttribution(1, list = 11)
        add(ring, span(1, target.intendedNs + 1, target.intendedNs + 2, attr = attr))
        add(ring, span(2, target.intendedNs + 3, target.intendedNs + 4, attr = attr).copy(stage = "list.place"))
        assertTrue(ring.reserveFrame(severe = false))
        val evidence = ring.protectFrame(target)
        assertFalse(evidence.sourceWindowLoss); assertFalse(evidence.sourceWindowUnknown)
        val geometry = listSamples(target, 11, 22).forFrame(target, evidence)
        assertEquals(11L, geometry!!.list)
        ring.frame(target.copy(listSnapshot = geometry))
        val detail = ring.drain(1_000_000_000)
        assertEquals(0L, detail.frameCaptureTruncated)
        assertEquals(11L, detail.frames.single().listSnapshot!!.list)
    }

    @Test fun previousWindowSecondSourceBeyond512ProtectedSpansStillRejectsUniqueness() {
        val target = listFrame()
        val ring = BoundedDiagnosticDetails(0, capacity = FRAME_CAPTURE_MAX_SPANS)
        val secondId = FRAME_CAPTURE_MAX_SPANS.toLong() + 1
        add(ring, span(secondId, target.intendedNs + 3_000_000, target.intendedNs + 3_500_000,
            attr = StreamDiagnosticAttribution(1, list = 22)).copy(stage = "list.place"))
        ring.snapshot(505_000_000)
        addCapturePrefix(ring, target) // Late admissions of the same frame now live in the current window.
        assertTrue(ring.reserveFrame(severe = false))
        val evidence = ring.protectFrame(target)
        assertFalse(evidence.sourceWindowLoss); assertFalse(evidence.sourceWindowUnknown)
        assertEquals(513, evidence.spans.size)
        assertEquals(secondId, evidence.spans.last().span)
        val geometry = listSamples(target, 11, 22).forFrame(target, evidence)
        assertNull(geometry)
        ring.frame(target.copy(listSnapshot = geometry))
        val detail = ring.drain(1_000_000_000)
        assertEquals(1L, detail.frameCaptureTruncated)
        assertEquals(0L, detail.previousWindowSpans) // Source matching sees previous evidence even if protection omits it.
        assertNull(detail.frames.single().listSnapshot)
    }

    @Test fun previousWindowUniqueListSourceStillMatchesWithoutRecapturing() {
        val target = listFrame()
        val ring = BoundedDiagnosticDetails(0, capacity = 2)
        add(ring, span(1, target.intendedNs + 1, target.intendedNs + 2,
            attr = StreamDiagnosticAttribution(1, list = 11)))
        ring.snapshot(505_000_000)
        assertTrue(ring.reserveFrame(severe = false))
        val evidence = ring.protectFrame(target)
        assertFalse(evidence.sourceWindowLoss); assertFalse(evidence.sourceWindowUnknown)
        assertEquals(listOf(1L), evidence.spans.map { it.span })
        val geometry = listSamples(target, 11, 22).forFrame(target, evidence)
        assertEquals(11L, geometry!!.list)
        ring.frame(target.copy(listSnapshot = geometry))
        val detail = ring.drain(1_000_000_000)
        assertEquals(1L, detail.previousWindowSpans)
        assertEquals(0L, detail.frameCaptureTruncated)
        assertEquals(11L, detail.frames.single().listSnapshot!!.list)
    }

    @Test fun sourceWindowLossAndUnknownRejectEvenApparentlyUniqueCapturedSourcesAndFallback() {
        val target = listFrame()
        val samples = listSamples(target, 11)
        val known = span(1, target.intendedNs + 1, target.intendedNs + 2,
            attr = StreamDiagnosticAttribution(1, list = 11))
        val currentLoss = BoundedDiagnosticDetails(0, capacity = 1)
        add(currentLoss, known)
        add(currentLoss, known.copy(span = 2, stage = "row.measure")) // The list scope was overwritten.
        assertTrue(currentLoss.reserveFrame(severe = false))
        val fallback = currentLoss.protectFrame(target)
        assertTrue(fallback.sourceWindowLoss)
        assertNull(samples.forFrame(target, fallback))

        val previousLoss = BoundedDiagnosticDetails(0, capacity = 1)
        add(previousLoss, known.copy(span = 2, attribution = StreamDiagnosticAttribution(1, list = 22)))
        add(previousLoss, known)
        previousLoss.snapshot(505_000_000)
        assertTrue(previousLoss.reserveFrame(severe = false))
        val lost = previousLoss.protectFrame(target)
        assertTrue(lost.sourceWindowLoss)
        assertEquals(listOf(11L), lost.spans.map { it.attribution!!.list })
        assertNull(samples.forFrame(target, lost))

        val slowLoss = BoundedDiagnosticDetails(0, capacity = 2, slowLimit = 1)
        add(slowLoss, known.copy(endNs = known.beginNs + 4_000_000))
        add(slowLoss, known.copy(span = 2, stage = "row.measure", endNs = known.beginNs + 4_000_000))
        assertTrue(slowLoss.reserveFrame(severe = false))
        val dropped = slowLoss.protectFrame(target)
        assertTrue(dropped.sourceWindowLoss)
        assertNull(samples.forFrame(target, dropped))

        val tooOld = BoundedDiagnosticDetails(0, capacity = 1)
        tooOld.snapshot(505_000_000); tooOld.snapshot(510_000_000)
        add(tooOld, known) // A retained late span does not prove the older missing generation complete.
        assertTrue(tooOld.reserveFrame(severe = false))
        val unknown = tooOld.protectFrame(target)
        assertTrue(unknown.sourceWindowUnknown)
        assertEquals(listOf(11L), unknown.spans.map { it.attribution!!.list })
        assertNull(samples.forFrame(target, unknown))
    }

    @Test fun nestedInclusiveSpansUseUnionNotSumAndDirectChildrenForSelfUpperBound() {
        val parent = span(1, 90, 210)
        val child1 = span(2, 100, 160, parent = 1)
        val child2 = span(3, 150, 190, parent = 1)
        val grandchild = span(4, 110, 140, parent = 2)
        val spans = listOf(parent, child1, child2, grandchild, child1)
        val result = correlateDiagnosticFrame(frame(), spans)
        assertEquals(4, result.totalOverlaps)
        assertEquals(100L, result.overlapUnionNs)
        assertEquals(0L, result.frameWallOutsideSpansNs)
        assertEquals(30L, diagnosticSelfUpperBoundNs(parent, spans))
        assertEquals(30L, diagnosticSelfUpperBoundNs(child1, spans))
    }

    @Test fun outputBudgetIsCountedWithoutChangingUnionOrImplyingCausality() {
        val result = correlateDiagnosticFrame(frame(), listOf(span(1, 100, 200), span(2, 110, 150),
            span(3, 120, 130)), limit = 1)
        assertEquals(3, result.totalOverlaps)
        assertEquals(2, result.omitted)
        assertEquals(listOf(1L), result.overlaps.map { it.span })
        assertEquals(100L, result.overlapUnionNs)
        val empty = correlateDiagnosticFrame(frame(), emptyList())
        assertTrue(empty.overlaps.isEmpty())
        assertEquals(100L, empty.frameWallOutsideSpansNs) // NOT proof of idle CPU or the cause of a miss.
    }

    @Test fun shortFloodCannotOverwriteSeparateSlowEvidence() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2, slowLimit = 8)
        add(ring, span(1, 0, 20_000_000))
        repeat(30) { add(ring, span(2L + it, 21_000_000L + it, 21_000_001L + it)) }
        val detail = ring.drain(30_000_000)
        assertTrue(detail.overwritten > 0)
        assertEquals(listOf(1L), detail.spans.map { it.span })
        assertEquals(0L, detail.spanOutputTruncated)
    }

    @Test fun frameCallbackProtectionSurvivesLaterShortFloodAndPrioritizesTheFrame() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2, slowLimit = 1)
        add(ring, span(1, 0, 4_000_000)) // Unrelated older slow work would previously consume output budget.
        add(ring, span(2, 10_000_001, 10_000_002))
        val frame = frame(10_000_000, 100)
        ring.protectFrame(frame)
        repeat(20) { add(ring, span(3L + it, 20_000_000L + it, 20_000_001L + it)) }
        ring.frame(frame)
        val detail = ring.drain(30_000_000)
        assertEquals(listOf(2L), detail.spans.map { it.span })
        assertEquals(1L, detail.spanOutputTruncated)
    }

    @Test fun delayedFrameCanUseOnlyOnePreviousDetachedWindow() {
        val ring = BoundedDiagnosticDetails(0, capacity = 8)
        add(ring, span(1, 0, 10_000_000))
        ring.snapshot(12_000_000)
        val delayed = frame(1_000_000, 5_000_000)
        ring.protectFrame(delayed)
        ring.frame(delayed)
        val selected = ring.drain(20_000_000)
        assertEquals(listOf(1L), selected.spans.map { it.span })
        assertEquals(1L, selected.previousWindowSpans)
        ring.snapshot(30_000_000)
        ring.protectFrame(delayed)
        ring.frame(delayed)
        assertTrue(ring.drain(40_000_000).spans.isEmpty())
    }

    @Test fun finalRejectsProtectionAndNewSessionCannotReuseOldWindowEvidence() {
        val old = BoundedDiagnosticDetails(0)
        add(old, span(1, 100, 200))
        old.snapshot(1000, final = true)
        old.protectFrame(frame())
        assertTrue(old.drain(2000).spans.isEmpty())
        val fresh = BoundedDiagnosticDetails(0)
        fresh.protectFrame(frame()); fresh.frame(frame())
        assertTrue(fresh.drain(1000).spans.isEmpty())
    }

    @Test fun anonymizedRowTokensAreStableSeparateBoundedAndNeverRecycled() {
        val tokens = DiagnosticRowTokens(2)
        assertEquals(1, tokens.token("PRIVATE_ROW"))
        assertEquals(2, tokens.token("PRIVATE_OTHER_ROW"))
        assertEquals(1, tokens.token("PRIVATE_ROW"))
        assertEquals(0, tokens.token("THIRD_ROW"))
        assertEquals(1L, tokens.saturated)
        assertEquals(1, tokens.token("PRIVATE_ROW"))
        val attr = StreamDiagnosticAttribution(7, row = 1, rowType = diagnosticRowType("PRIVATE"),
            block = 3, blockType = diagnosticBlockType("PRIVATE"), component = "PRIVATE")
        assertFalse(diagnosticRenderFields(attr).contains("PRIVATE"))
        assertEquals(1, DiagnosticRowTokens(2).token("THIRD_ROW")) // Tokens are valid only with the session ID.
    }

    @Test fun geometryNeverBorrowsAFutureSampleAndKeepsBoundedVisibleSlots() {
        val samples = DiagnosticListSamples(2)
        fun sample(at: Long) = DiagnosticListSnapshot(at, 3, 12, 14, 8, 4, 0, 900, 1,
            listOf(DiagnosticListRow(5, 8, -4, 90)), page = 5, segment = 1)
        val sourceFrame = frame().copy(page = 5, pageEnd = 5, pageSegment = 1)
        val evidence = DiagnosticFrameEvidence(emptyList())
        samples.add(sample(150)); samples.add(sample(250))
        assertEquals(150L, samples.forFrame(sourceFrame, evidence)!!.atNs)
        assertNull(samples.forFrame(sourceFrame.copy(intendedNs = 0, totalNs = 100), evidence))
        samples.add(sample(300))
        assertEquals(1L, samples.overwritten)
        assertNull(samples.forFrame(sourceFrame, evidence))
    }

    @Test fun sourceGeometryPrefersMatchedListAndRejectsTransitionsUnknownAndAmbiguity() {
        val samples = DiagnosticListSamples()
        fun sample(list: Long, page: Int = 5, segment: Long = 7) = DiagnosticListSnapshot(150, list,
            1, 1, 0, 0, 0, 100, 1, emptyList(), page, segment)
        samples.add(sample(11)); samples.add(sample(22)); samples.add(sample(33, page = 4))
        val target = frame().copy(page = 5, pageEnd = 5, pageSegment = 7)
        fun listSpan(list: Long) = span(list, 100, 200, attr = StreamDiagnosticAttribution(1, list = list))
        val matched = DiagnosticFrameEvidence(listOf(listSpan(11)))
        assertNull(samples.forFrame(target, DiagnosticFrameEvidence(emptyList())))
        assertEquals(11L, samples.forFrame(target, matched)!!.list)
        assertNull(samples.forFrame(target, DiagnosticFrameEvidence(listOf(listSpan(11), listSpan(22)))))
        assertNull(samples.forFrame(target, DiagnosticFrameEvidence(listOf(listSpan(33)))))
        assertNull(samples.forFrame(target, DiagnosticFrameEvidence(listOf(span(99, 100, 200)))))
        assertNull(samples.forFrame(target.copy(changed = true), matched))
        assertNull(samples.forFrame(target.copy(pageSegment = 8), matched))
        assertNull(samples.forFrame(target.copy(page = 0), matched))
        assertNull(samples.forFrame(target, matched.copy(sourceWindowLoss = true)))
        assertNull(samples.forFrame(target, matched.copy(sourceWindowUnknown = true)))
        assertNull(samples.forFrame(target.copy(sourceWindowLoss = true), matched))
        assertNull(samples.forFrame(target.copy(sourceWindowUnknown = true), matched))
    }

    @Test fun slowBudgetFullStillAdmitsNewSevereSpanForProtection() {
        val ring = BoundedDiagnosticDetails(0, capacity = 2, slowLimit = 1)
        add(ring, span(1, 0, 4_000_000))
        add(ring, span(2, 100_000_000, 140_000_000))
        val target = frame(100_000_000, 50_000_000)
        ring.protectFrame(target)
        repeat(10) { add(ring, span(3L + it, 200_000_000L + it, 200_000_001L + it)) }
        ring.frame(target)
        val selected = ring.drain(300_000_000)
        assertEquals(1L, selected.slowBudgetDropped)
        assertEquals(2L, selected.spans.single().span)
    }

    @Test fun deadlineOnlyCannotConsumeSevereReserveAndSevereCanReplaceLightFrame() {
        val ring = BoundedDiagnosticDetails(0, frameLimit = 4)
        repeat(3) { assertTrue(ring.reserveFrame(severe = false)); ring.frame(frame(it.toLong())) }
        assertFalse(ring.reserveFrame(severe = false))
        val severe = frame(1000, 40_000_000)
        assertTrue(ring.reserveFrame(severe = true)); ring.frame(severe)
        assertTrue(ring.reserveFrame(severe = true)); ring.frame(severe.copy(intendedNs = 2000))
        val selected = ring.drain(50_000_000)
        assertEquals(4, selected.frames.size)
        assertEquals(2, selected.frames.count { it.severe })
        assertEquals(1L, selected.frameBudgetDropped)
        assertEquals(1L, selected.frameBudgetEvicted)
    }

    @Test fun openDispatchAtStopIsPartialAndNeverHasFabricatedCpu() {
        val log = MainThreadMessageLog(cpuClock = { 42L })
        log.onLine(">>>>> Dispatching to Handler (test.Handler) {1} test.Callback@1: 0", 0)
        log.addCovered("row.measure", 0, 10, 10)
        log.closeOpen(100)
        val partial = log.timingsBetween(0, 100).single()
        assertTrue(partial.partial); assertEquals(-1L, partial.cpuNs)
        assertEquals(100L, partial.endNs); assertEquals(10L, partial.coveredNs)
        log.onLine("<<<<< Finished", 200)
        assertEquals(1, log.timingsBetween(0, 300).size)
    }

    @Test fun cpuCounterIsOptionalInjectableAndNeverClaimsThatWallMinusCpuIsBlocking() {
        val clocks = listOf(20L, 27L).iterator()
        val log = MainThreadMessageLog(cpuClock = { clocks.next() })
        log.onLine(">>>>> Dispatching to Handler (test.Handler) {1} test.Callback@1: 0", 0)
        log.onLine("<<<<< Finished", 10_000_000)
        val message = log.timingsBetween(0, 20_000_000).single()
        assertEquals(7L, message.cpuNs)
        val unsupported = MainThreadMessageLog()
        unsupported.onLine(">>>>> Dispatching to Handler (test.Handler) {1} test.Callback@1: 0", 0)
        unsupported.onLine("<<<<< Finished", 10_000_000)
        assertEquals(-1L, unsupported.timingsBetween(0, 20_000_000).single().cpuNs)
    }
}
