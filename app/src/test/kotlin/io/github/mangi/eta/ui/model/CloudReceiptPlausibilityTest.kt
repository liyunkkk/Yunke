package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReceiptPlausibilityTest {

    @Test fun anAggregatedBillFarAboveTheWindowIsNotOccupancy() {
        // Observed receipt: 784267 reported for a request that succeeded on a 500000 window.
        assertFalse(CloudReceiptPlausibility.isOccupancy(784_267, contextWindow = 500_000))
    }

    @Test fun agenuineOverflowJustAboveTheWindowStaysVisible() {
        // The user really can send 220k with a 200k setting; that must not be hidden.
        assertTrue(CloudReceiptPlausibility.isOccupancy(220_000, contextWindow = 200_000))
    }

    @Test fun aBillManyTimesTheLocalTranscriptIsRejected() {
        // round 14 -> 15: billed 267917 while the locally counted request was ~22194.
        assertFalse(CloudReceiptPlausibility.isOccupancy(
            267_917, contextWindow = 1_000_000, localTokens = 22_194))
    }

    @Test fun normalGrowthOverTheLocalBasisIsAccepted() {
        // Cache-heavy prompts legitimately bill somewhat above the local estimate.
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            38_880, contextWindow = 1_000_000, localTokens = 21_079))
    }

    @Test fun aSmallLocalBasisDisablesOnlyTheRatioTest() {
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            9_000, contextWindow = 200_000, localTokens = 300))
        assertFalse(CloudReceiptPlausibility.isOccupancy(
            900_000, contextWindow = 200_000, localTokens = 300))
    }

    @Test fun missingWindowAndNonPositiveValuesAreHandled() {
        assertTrue(CloudReceiptPlausibility.isOccupancy(500_000, contextWindow = null))
        assertFalse(CloudReceiptPlausibility.isOccupancy(0, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(null, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(-5, contextWindow = null))
    }
}
