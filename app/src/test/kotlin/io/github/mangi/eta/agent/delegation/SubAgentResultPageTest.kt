package io.github.mangi.eta.agent.delegation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentResultPageTest {
    private fun record(status: String = "completed", report: String = "report") = JSONObject()
        .put("status", status).put("role", "research").put("result", report)
        .put("partial_result", report).put("model_report_unverified", "unverified model claim")
        .put("acceptance_verified", false)
    private fun args(field: String, offset: Int = 0, limit: Int = 4000) = JSONObject()
        .put("text_field", field).put("text_offset", offset).put("text_limit", limit)

    @Test fun runningPollsDoNotReplayProseButExplicitIncrementalPagesRemainReadable() {
        for (status in listOf("queued", "running")) {
            val default = SubAgentResultPage.project(record(status))
            SubAgentResultPage.fields.forEach { assertEquals("", default.getString(it)) }
            assertFalse(default.has("text_page"))
            val explicit = SubAgentResultPage.project(record(status), args("partial_result", 2, 3))
            assertEquals("por", explicit.getString("partial_result"))
            assertEquals(5, explicit.getJSONObject("text_page").getInt("next_offset"))
            assertEquals("", explicit.getString("result"))
            assertTrue(explicit.getJSONObject("text_page").getBoolean("unverified"))
        }
    }

    @Test fun finalDefaultIsOnePageNotDuplicatedProseAndPagesReassembleTheWholeReport() {
        val full = "R".repeat(19000)
        var page = SubAgentResultPage.project(record(report = full))
        assertEquals(4000, page.getString("result").length)
        assertEquals("", page.getString("partial_result"))
        assertEquals("", page.getString("model_report_unverified"))
        assertEquals("result", page.getJSONObject("text_fields").getJSONObject("partial_result").getString("same_as"))
        val rebuilt = StringBuilder(page.getString("result"))
        while (page.getJSONObject("text_page").getBoolean("has_more")) {
            page = SubAgentResultPage.project(record(report = full), args("result", page.getJSONObject("text_page").getInt("next_offset")))
            rebuilt.append(page.getString("result"))
        }
        assertEquals(full, rebuilt.toString())
    }

    @Test fun modelClaimsAreOptInAndDoNotChangeArtifactOrAcceptanceEvidence() {
        val evidence = JSONObject().put("artifact_commit", "trusted-receipt")
        val original = record().put("role", "implementation").put("artifact_verified", true).put("artifact_evidence", evidence)
        val page = SubAgentResultPage.project(original, args("model_report_unverified"))
        assertEquals("unverified model claim", page.getString("model_report_unverified"))
        assertTrue(page.getJSONObject("text_page").getBoolean("unverified"))
        assertFalse(page.getBoolean("acceptance_verified"))
        assertEquals(evidence.toString(), page.getJSONObject("artifact_evidence").toString())
        assertEquals("", page.getString("result"))
    }

    @Test fun emptyAndPastEndPagesHaveNoMoreTextAndOffsetsAreIndependentOfEventSequence() {
        val page = SubAgentResultPage.project(record(), args("result", 999).put("after_seq", 100000L))
        assertEquals("", page.getString("result"))
        assertEquals(6, page.getJSONObject("text_page").getInt("next_offset"))
        assertFalse(page.getJSONObject("text_page").getBoolean("has_more"))
        val empty = SubAgentResultPage.project(record(report = ""), args("result"))
        assertEquals(0, empty.getJSONObject("text_page").getInt("total_chars"))
        assertTrue(empty.getJSONObject("text_page").getBoolean("available"))
    }

    @Test fun pageBoundariesDoNotSplitEmojiAndPaginationMakesProgress() {
        val full = "abc🙂xyz"
        val first = SubAgentResultPage.project(record(report = full), args("result", limit = 4))
        assertEquals("abc", first.getString("result"))
        val second = SubAgentResultPage.project(record(report = full), args("result", 3, 2))
        assertEquals("🙂", second.getString("result"))
        assertEquals(5, second.getJSONObject("text_page").getInt("next_offset"))
    }

    @Test fun revisionsAreStableAcrossPagesButInvalidateSameLengthChangedText() {
        val first = SubAgentResultPage.project(record(report = "old report"), args("partial_result", 0, 2))
        val last = SubAgentResultPage.project(record(report = "old report"), args("result", 6, 2))
        assertEquals(first.getString("text_revision"), last.getString("text_revision"))
        val changed = SubAgentResultPage.project(record(report = "new report"))
        assertNotEquals(first.getString("text_revision"), changed.getString("text_revision"))
        assertTrue(first.getString("text_revision").matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun offsetsInsidePairsAreRejectedButSingleUnitLimitsStillMakeProgress() {
        assertThrows(IllegalArgumentException::class.java) {
            SubAgentResultPage.project(record(report = "🙂A"), args("result", 1, 1))
        }
        val page = SubAgentResultPage.project(record(report = "🙂A"), args("result", 0, 1))
        assertEquals("🙂", page.getString("result"))
        assertEquals(2, page.getJSONObject("text_page").getInt("next_offset"))
    }

    @Test fun invalidFieldsAndOffsetsCannotRequestAnUnboundedResponse() {
        for (bad in listOf(JSONObject().put("text_limit", 16000), args("unknown"),
            args("result", -1), args("result", limit = 0), args("result", limit = 16001),
            args("result").put("text_offset", 1.5), args("result").put("text_offset", "1"),
            args("result").put("text_offset", Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { SubAgentResultPage.project(record(), bad) }
        }
    }

    @Test fun archiveEvictionIsExplicitAndNeverPretendsThereIsACompleteReport() {
        val page = SubAgentResultPage.project(record(report = "").put("text_evicted", true), args("result"))
        assertFalse(page.getJSONObject("text_page").getBoolean("available"))
        assertFalse(page.getJSONObject("text_page").getBoolean("has_more"))
        assertEquals("archive_capacity", page.getJSONObject("text_fields").getJSONObject("result").getString("unavailable_reason"))
    }
}
