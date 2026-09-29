package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReceiptPlausibilityTest {

    @Test fun theExistingAbsoluteWindowPolicyIsPreserved() {
        assertFalse(CloudReceiptPlausibility.isOccupancy(784_267, contextWindow = 500_000))
        assertTrue(CloudReceiptPlausibility.isOccupancy(260_000, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(260_001, contextWindow = 200_000))
    }

    @Test fun aGenuineOverflowJustAboveTheWindowStaysVisible() {
        assertTrue(CloudReceiptPlausibility.isOccupancy(220_000, contextWindow = 200_000))
    }

    @Test fun measuredConversationReceiptsDoNotStickAtTheFourthRound() {
        // Actual cloud inputs from the affected conversation, including the matched ST API receipt.
        listOf(23_104, 54_244, 76_783, 106_018, 133_457, 158_537, 270_648).forEach { input ->
            assertTrue("Measured input $input must replace the previous receipt",
                CloudReceiptPlausibility.isOccupancy(input, contextWindow = 272_000))
        }
    }

    @Test fun cloudPromptAcceptanceDoesNotDependOnLocalGrowth() {
        // The policy intentionally has no local-estimate or previous-receipt input.
        assertTrue(CloudReceiptPlausibility.isOccupancy(100_000, contextWindow = 200_000))
        assertTrue(CloudReceiptPlausibility.isOccupancy(267_917, contextWindow = 272_000))
    }

    @Test fun aSmallerCloudReceiptIsAccepted() {
        assertTrue(CloudReceiptPlausibility.isOccupancy(26_424, contextWindow = 500_000))
    }

    @Test fun missingWindowAndNonPositiveValuesAreHandled() {
        assertTrue(CloudReceiptPlausibility.isOccupancy(500_000, contextWindow = null))
        assertFalse(CloudReceiptPlausibility.isOccupancy(0, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(null, contextWindow = 200_000))
        assertFalse(CloudReceiptPlausibility.isOccupancy(-5, contextWindow = null))
    }
}
