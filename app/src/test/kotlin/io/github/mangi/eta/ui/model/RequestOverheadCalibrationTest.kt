package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class RequestOverheadCalibrationTest {
    private fun sample() = requireNotNull(RequestOverheadCalibration.learn(null, 37214, 481, 25273))

    @Test fun overheadDominatedReceiptCorrectsFirstMessageAndPostCompressionFallback() {
        val calibration = sample()
        assertEquals(11460, calibration.offsetTokens)
        assertEquals(1, calibration.samples)
        assertEquals(25273, calibration.measuredOverheadTokens)
        val live = liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 500, requestOverheadTokens = 25273,
            overheadCalibrationTokens = calibration.offsetTokens)
        val compressed = compressionContextUsage(emptyList(), "", emptyList(), null,
            localHistoryTokenCount = 500, requestOverheadTokens = 25273,
            overheadCalibrationTokens = calibration.offsetTokens)
        assertEquals(37233, live.contextTokens)
        assertEquals(live.contextTokens, compressed.contextTokens)
        assertTrue(kotlin.math.abs(requireNotNull(live.contextTokens) - 37214) < 37214 * 0.01)
        assertTrue(live.estimated)
        // Tool/skill changes still move the local overhead; the correction is additive.
        assertEquals(38733, calibration.apply(27273))
    }

    @Test fun longHistoryReceiptCannotPolluteFixedOverheadCalibration() {
        val previous = sample()
        assertNull(RequestOverheadCalibration.learn(previous, 67035, 24626, 25271))
        assertEquals(11460, previous.offsetTokens)
        assertEquals(1, previous.samples)
    }

    @Test fun abnormalNegativeAndInflatedReceiptsAreIgnored() {
        val previous = sample()
        assertNull(RequestOverheadCalibration.learn(previous, 100000, 481, 25273))
        assertNull(RequestOverheadCalibration.learn(previous, 10000, 481, 25273))
        assertNull(RequestOverheadCalibration.learn(previous, 37214, 481, 25273, inflatedCache = true))
        assertNull(RequestOverheadCalibration.learn(previous, 0, 0, 25273))
        assertNull(RequestOverheadCalibration.learn(previous, 1000, -1, 25273))
    }

    @Test fun acceptedSamplesAreSmoothedAndKeepMeasuredOverhead() {
        val second = requireNotNull(RequestOverheadCalibration.learn(sample(), 35000, 500, 25000))
        assertEquals((11460 + 9500) / 2, second.offsetTokens)
        assertEquals(2, second.samples)
        assertEquals(25000, second.measuredOverheadTokens)
        assertEquals(0, RequestOverheadCalibration.applyOffset(100, -200))
        assertEquals(Int.MAX_VALUE, RequestOverheadCalibration.applyOffset(Int.MAX_VALUE, 11460))
    }

    @Test fun validReceiptsDoNotReceiveTheOffsetTwice() {
        val liveBefore = liveContextUsage(emptyList(), "new draft", emptyList(), null,
            historyTokenCount = 500, billedContextTokens = 37214, requestOverheadTokens = 25273,
            projectedContextTokens = 12345)
        val liveAfter = liveContextUsage(emptyList(), "new draft", emptyList(), null,
            historyTokenCount = 500, billedContextTokens = 37214, requestOverheadTokens = 25273,
            projectedContextTokens = 12345, overheadCalibrationTokens = 11460)
        assertEquals(liveBefore, liveAfter)
        val budgetBefore = compressionContextUsage(emptyList(), "new draft", emptyList(), null,
            historyTokenCount = 800, billedContextTokens = 37214, requestOverheadTokens = 26000,
            billedHistoryTokens = 481, billedOverheadTokens = 25273)
        val budgetAfter = compressionContextUsage(emptyList(), "new draft", emptyList(), null,
            historyTokenCount = 800, billedContextTokens = 37214, requestOverheadTokens = 26000,
            billedHistoryTokens = 481, billedOverheadTokens = 25273, overheadCalibrationTokens = 11460)
        assertEquals(budgetBefore, budgetAfter)
    }

    @Test fun calibratedFallbackUsesLegacyUnitsInsteadOfFinalBodyProjection() {
        val usage = liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 500, requestOverheadTokens = 25273, projectedContextTokens = 20000,
            overheadCalibrationTokens = 11460)
        assertEquals(37233, usage.contextTokens)
        val compression = compressionContextUsage(emptyList(), "", emptyList(), null,
            localHistoryTokenCount = 500, requestOverheadTokens = 25273, projectedContextTokens = 20000,
            overheadCalibrationTokens = 11460)
        assertEquals(37233, compression.contextTokens)
    }

    @Test fun defaultParameterPreservesExistingLocalAndProjectedEstimates() {
        assertEquals(25773, liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 500, requestOverheadTokens = 25273).contextTokens)
        assertEquals(20000, liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 500, requestOverheadTokens = 25273, projectedContextTokens = 20000).contextTokens)
        assertEquals(25773, compressionContextUsage(emptyList(), "", emptyList(), null,
            localHistoryTokenCount = 500, requestOverheadTokens = 25273).contextTokens)
    }
}
