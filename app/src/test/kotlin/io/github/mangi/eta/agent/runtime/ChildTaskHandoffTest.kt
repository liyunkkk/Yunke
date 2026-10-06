package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentResultPage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChildTaskHandoffTest {
    private fun record(text: String = "PRIVATE_REPORT") = JSONObject().put("ok", true).put("task_id", "child")
        .put("status", "failed").put("error_code", "SUB_AGENT_FAILED").put("execution_exited", true)
        .put("can_replace", true).put("result", text).put("partial_result", text)

    @Test fun readingAnExplicitTextPageDoesNotInvalidateHandoffOnDefaultOrListProbes() {
        val handoffs = ChildTaskHandoff()
        val read = handoffs.observe(SubAgentResultPage.project(record(), JSONObject()
            .put("text_field", "partial_result").put("text_offset", 3).put("text_limit", 2)))
        handoffs.recordRead(read)
        val version = read.getLong("handoff_version")
        val probe = handoffs.observe(SubAgentResultPage.project(record()))
        assertEquals(version, probe.getLong("handoff_version"))
        assertTrue(handoffs.matchesRead("child", version))
        val archive = handoffs.observe(SubAgentResultPage.attachRevision(record()))
        assertEquals(version, archive.getLong("handoff_version"))
        val evicted = handoffs.observe(SubAgentResultPage.attachRevision(record())
            .put("result", "").put("partial_result", "").put("text_evicted", true))
        assertEquals(version, evicted.getLong("handoff_version"))
        assertTrue(handoffs.matchesRead("child", version))
    }

    @Test fun realSameLengthReportChangesInvalidateReadButInternalProbesCannotInventReads() {
        val handoffs = ChildTaskHandoff()
        val first = handoffs.observe(SubAgentResultPage.project(record("old")))
        assertNull(handoffs.readVersion("child"))
        handoffs.recordRead(first)
        val next = handoffs.observe(SubAgentResultPage.project(record("new")))
        assertTrue(next.getLong("handoff_version") > first.getLong("handoff_version"))
        assertFalse(handoffs.matchesRead("child", next.getLong("handoff_version")))
    }

    @Test fun retainedObservationsContainOnlyFixedLengthFingerprintsAndEvictedTasksAreForgotten() {
        val handoffs = ChildTaskHandoff()
        val observed = handoffs.observe(record("PRIVATE_HUGE_REPORT".repeat(100000)))
        handoffs.recordRead(observed)
        val map = ChildTaskHandoff::class.java.getDeclaredField("observations").apply { isAccessible = true }
            .get(handoffs) as Map<*, *>
        val observation = requireNotNull(map["child"])
        val fingerprint = observation.javaClass.getDeclaredField("fingerprint").apply { isAccessible = true }
            .get(observation) as String
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertFalse(fingerprint.contains("PRIVATE_HUGE_REPORT"))
        handoffs.retainOnly(emptySet())
        assertTrue(map.isEmpty())
        assertNull(handoffs.readVersion("child"))
    }
}
