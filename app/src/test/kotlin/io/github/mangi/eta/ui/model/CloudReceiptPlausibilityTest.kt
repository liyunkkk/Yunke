package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReceiptPlausibilityTest {

    @Test fun anAggregatedBillFarAboveTheWindowIsNotOccupancy() {
        // Observed receipt: 784267 reported for a request that succeeded on a 500000 window.
        assertFalse(CloudReceiptPlausibility.isOccupancy(784_267, contextWindow = 500_000))
    }

    @Test fun aGenuineOverflowJustAboveTheWindowStaysVisible() {
        // The user really can send 220k with a 200k setting; that must not be hidden.
        assertTrue(CloudReceiptPlausibility.isOccupancy(220_000, contextWindow = 200_000))
    }

    @Test fun aStepFarBeyondTheTranscriptGrowthIsRejected() {
        // round 14 -> 15: billed +229037 while the local request grew 21079 -> 22194.
        assertFalse(CloudReceiptPlausibility.isOccupancy(
            267_917, contextWindow = 200_000,
            previousTokens = 38_880, localTokens = 22_194, previousLocalTokens = 21_079))
    }

    @Test fun ordinaryPerRoundGrowthIsAccepted() {
        // round 13 -> 14 from the same run: +1113 billed, +1154 counted locally.
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            38_880, contextWindow = 200_000,
            previousTokens = 37_767, localTokens = 21_079, previousLocalTokens = 19_925))
    }

    @Test fun aFirstBillManyTimesTheLocalEstimateIsStillAccepted() {
        // Without a previous anchor there is no growth to compare, and relays that bill
        // inline images as base64 legitimately report far above the local count.
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            131_470, contextWindow = 200_000, localTokens = 9_000))
    }

    @Test fun aDecreasingBillIsAlwaysAllowed() {
        // Compaction and cache changes can genuinely lower occupancy.
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            26_424, contextWindow = 500_000,
            previousTokens = 784_267, localTokens = 7_927, previousLocalTokens = 9_714))
    }

    @Test fun slackScalesWithTheWindowSoLargeContextRoundsAreNotRejected() {
        // A 1M window tolerates a bigger absolute step than a 200k one.
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            100_000, contextWindow = 1_000_000,
            previousTokens = 50_000, localTokens = 40_000, previousLocalTokens = 39_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(
            100_000, contextWindow = 200_000,
            previousTokens = 50_000, localTokens = 40_000, previousLocalTokens = 39_000))
    }

    @Test fun missingReferencesAndNonPositiveValuesAreHandled() {
        assertTrue(CloudReceiptPlausibility.isOccupancy(500_000, contextWindow = null))
        assertTrue(CloudReceiptPlausibility.isOccupancy(
            99_999, contextWindow = 200_000, previousTokens = 1_000, localTokens = null))
        assertFalse(CloudReceiptPlausibility.isOccupancy(0, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(null, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(-5, contextWindow = null))
    }
}
