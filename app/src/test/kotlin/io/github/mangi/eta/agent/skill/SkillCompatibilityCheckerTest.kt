package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCompatibilityCheckerTest {
    @Test
    fun rejectsMinisAndroidCli() {
        val result = SkillCompatibilityChecker.evaluate(
            id = "android-ui-automation",
            description = "Automate apps with Accessibility.",
            compatibility = "requires android-a11y-cli / android-open",
        )
        assertFalse(result.available)
        assertEquals("依赖 MiniS 专属 Android CLI，芸珂 无法运行", result.reason)
    }

    @Test
    fun rejectsHealthKitAndIosOnly() {
        assertFalse(
            SkillCompatibilityChecker.evaluate(
                id = "health-sleep-analysis",
                description = "Analyze Apple HealthKit sleep stages.",
            ).available,
        )
        assertFalse(
            SkillCompatibilityChecker.evaluate(
                id = "shortcut-share-file",
                description = "Share iCloud Drive files using the iOS Shortcut Instant Share.",
            ).available,
        )
    }

    @Test
    fun allowsHubSkillsAndAndroidPdfPipeline() {
        assertTrue(
            SkillCompatibilityChecker.evaluate(
                id = "bilibili-hub",
                description = "Obtain cookies through browser_use get_cookies for Bilibili.",
            ).available,
        )
        assertTrue(
            SkillCompatibilityChecker.evaluate(
                id = "pdf-converter",
                description = "Convert documents to PDF.",
                compatibility = "Python 3; Alpine (iSH) / Termux (Android) / Debian",
            ).available,
        )
    }
}
