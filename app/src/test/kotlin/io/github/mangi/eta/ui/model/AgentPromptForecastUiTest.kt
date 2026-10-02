package io.github.mangi.eta.ui.model

import io.github.mangi.eta.ui.components.nextRequestForecastLabel
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AgentPromptForecastUiTest {
    @Test fun runtimeForecastAddsOnlyUnsentDraft() {
        assertEquals(29517, nextRequestContextTokens(29497, 20, 29405, 99999))
        assertEquals(29497, nextRequestContextTokens(29497, 0, 29405, 99999))
    }

    @Test fun settledRunFallbackAlreadyIncludesTheDraft() {
        assertEquals(30000, nextRequestContextTokens(null, 123, 29405, 30000))
    }

    @Test fun unknownCloudKeepsOnlyTheExistingFirstBoundaryEstimate() {
        assertNull(nextRequestContextTokens(36590, 20, null, 36590))
        assertNull(nextRequestContextTokens(36590, 20, 0, 36590))
    }

    @Test fun missingPredictionIsNotShownAsZero() {
        assertNull(nextRequestContextTokens(null, 0, 29405, null))
        assertNull(nextRequestForecastLabel(null, 272000, Locale.US))
        assertNull(nextRequestForecastLabel(0, 272000, Locale.US))
    }

    @Test fun predictionLabelIsExplicitAndDoesNotChangeMeasuredUsage() {
        val measured = liveContextUsage(emptyList(), "", emptyList(), null,
            billedContextTokens = 29405)
        assertFalse(measured.estimated)
        assertEquals(29405, measured.contextTokens)
        val label = nextRequestForecastLabel(29497, 272000, Locale.US)
        assertTrue(requireNotNull(label).startsWith("下次请求预测 "))
        assertTrue(label!!.contains("≈"))
        assertFalse(formatContextUsage(measured, locale = Locale.US).contains("≈"))
    }

    @Test fun predictionsClampInsteadOfOverflowing() {
        assertEquals(Int.MAX_VALUE, nextRequestContextTokens(Int.MAX_VALUE, 100, 100, 100))
        assertEquals(100, nextRequestContextTokens(100, -50, 100, 100))
    }
}
