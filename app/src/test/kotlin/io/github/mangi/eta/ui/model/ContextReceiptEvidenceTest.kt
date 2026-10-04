package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class ContextReceiptEvidenceTest {
    @Test fun onlySameRequestAndUnchangedInputMayMergePartialBaseline() {
        val first = ContextReceiptEvidence("run:1", 37214, 481, 25270)
        assertEquals(first, ContextReceiptEvidence.merge(first, "run:1", 37214, null, null))
        val newRound = ContextReceiptEvidence.merge(first, "run:2", 40000, null, null)
        assertNull(newRound.history)
        assertNull(newRound.overhead)
        val changedInput = ContextReceiptEvidence.merge(first, "run:1", 40000, null, null)
        assertNull(changedInput.history)
        assertNull(changedInput.overhead)
        val complete = ContextReceiptEvidence.merge(first, "other-run:1", 25000, 1000, 20000)
        assertEquals(1000, complete.history)
        assertEquals(20000, complete.overhead)
    }
}
