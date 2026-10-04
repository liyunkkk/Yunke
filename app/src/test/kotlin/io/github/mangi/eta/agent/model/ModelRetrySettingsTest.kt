package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRetrySettingsTest {

    @Test
    fun defaultsToThreeRetries() {
        assertEquals(3, ModelRetrySettings.parseCount(null))
        assertEquals(3, ModelRetrySettings.parseCount(""))
        assertEquals(3, ModelRetrySettings.parseCount("   "))
        // 未写入配置时读取默认值，保持既有行为。
        assertEquals(ModelRetrySettings.DEFAULT_COUNT, ModelRetrySettings.configuredCount())
    }

    @Test
    fun acceptsOnlyAllowedCountsAndRejectsDirtyValues() {
        listOf(0, 3, 5, 8).forEach { count ->
            assertEquals(count, ModelRetrySettings.parseCount(count.toString()))
        }
        assertEquals(3, ModelRetrySettings.parseCount("4"))
        assertEquals(3, ModelRetrySettings.parseCount("-1"))
        assertEquals(3, ModelRetrySettings.parseCount("abc"))
        assertEquals(listOf(0, 3, 5, 8), ModelRetrySettings.allowedCounts)
    }

    @Test
    fun jitterDefaultsToDisabled() {
        assertFalse(ModelRetrySettings.jitterEnabled())
    }
}
