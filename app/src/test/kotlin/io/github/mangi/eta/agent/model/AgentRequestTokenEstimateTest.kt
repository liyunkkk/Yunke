package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRequestTokenEstimateTest {
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun image() = JSONObject().put("type", "image_url")
        .put("image_url", JSONObject().put("url", "data:image/png;base64,${"A".repeat(8192)}"))
    private fun video() = JSONObject().put("type", "video_url")
        .put("video_url", JSONObject().put("url", "data:video/mp4;base64,${"A".repeat(8192)}"))
    private fun message(vararg content: JSONObject) = JSONObject().put("role", "user")
        .put("content", JSONArray().also { array -> content.forEach { array.put(it) } })

    @Test fun emptyToolListCostsNothingButUncalledDefinitionsStillCount() {
        val history = JSONArray().put(message(text("hello")))
        val empty = AgentRequestTokenEstimate.boundary(history, JSONArray(), false, false)
        assertEquals(AgentContextBudget.countMessage(AgentModelClient.ConversationMessage(role = "user", content = "hello")), empty)
        val definitions = JSONArray().put(JSONObject().put("name", "unused_tool").put("description", "still sent"))
        assertEquals(empty + AgentContextBudget.countTokens(definitions.toString()),
            AgentRequestTokenEstimate.boundary(history, definitions, false, false))
        assertEquals(empty, AgentRequestTokenEstimate.filtered(AgentRequestMediaPolicy.filter(history, false, false), JSONArray()))
    }

    @Test fun textIsCountedOnceInArrayNotAsSerializedJson() {
        val history = JSONArray().put(message(text("hello world")))
        val expected = 3 + AgentContextBudget.countTokens("hello world")
        assertEquals(expected, AgentRequestTokenEstimate.boundary(history, JSONArray(), true, true))
        assertEquals(expected, AgentRequestTokenEstimate.filtered(AgentRequestMediaPolicy.filter(history, true, true), JSONArray()))
    }

    @Test fun capabilityFilterCountsOnlySentMediaAndActualOmissionNotice() {
        val history = JSONArray().put(message(text("look"), image(), video()))
        val tools = JSONArray()
        for (vision in listOf(false, true)) for (video in listOf(false, true)) {
            val filtered = AgentRequestMediaPolicy.filter(history, vision, video)
            val expected = AgentRequestTokenEstimate.filtered(filtered, tools)
            assertEquals("vision=$vision video=$video", expected,
                AgentRequestTokenEstimate.boundary(history, tools, vision, video))
            assertEquals(vision, filtered.toString().contains("image_url"))
            assertEquals(video, filtered.toString().contains("video_url"))
            assertEquals(!vision || !video, filtered.toString().contains("Media omitted"))
        }
    }

    @Test fun unsentMediaIsNotCountedAndOmissionTextIsCountedOncePerMessage() {
        val history = JSONArray().put(message(image(), image()))
        val filtered = AgentRequestMediaPolicy.filter(history, false, false)
        assertEquals(1, Regex("Media omitted").findAll(filtered.toString()).count())
        val estimate = AgentRequestTokenEstimate.filtered(filtered, JSONArray())
        assertEquals(3 + AgentContextBudget.countTokens(
            "[Media omitted: this model is not configured to accept it. Do not claim to have seen it. Choose a vision-capable model to inspect images.]"), estimate)
        assertEquals(estimate, AgentRequestTokenEstimate.boundary(history, JSONArray(), false, false))
    }

    @Test fun persistedMissingAttachmentsOnlyCountThePathListingActuallySent() {
        val path = "/missing/eta-request-token-test.jpg"
        val history = JSONArray().put(message(text("look"),
            JSONObject().put("type", "image_file").put("path", path)))
        for (vision in listOf(false, true)) {
            val filtered = AgentRequestMediaPolicy.filter(history, vision, false)
            assertEquals(AgentRequestTokenEstimate.filtered(filtered, JSONArray()),
                AgentRequestTokenEstimate.boundary(history, JSONArray(), vision, false))
            if (!vision) assertTrue(filtered.toString().contains("[用户图片]"))
            else assertFalse(filtered.toString().contains("image_url"))
        }
    }

    @Test fun replacedHistoryDoesNotCarryOldTokensAndNeverCountsContentTwice() {
        val tools = JSONArray()
        val before = JSONArray().put(message(text("x".repeat(1000))))
        val after = JSONArray().put(message(text("summary")))
        assertTrue(AgentRequestTokenEstimate.boundary(before, tools, false, false) >
            AgentRequestTokenEstimate.boundary(after, tools, false, false))
        assertEquals(AgentRequestTokenEstimate.boundary(after, tools, false, false),
            AgentRequestTokenEstimate.filtered(AgentRequestMediaPolicy.filter(after, false, false), tools))
    }

    @Test fun absentNullAndEmptyToolCallsHaveIdenticalCost() {
        val plain = message(text("hello"))
        val expected = AgentRequestTokenEstimate.boundary(JSONArray().put(plain), JSONArray(), false, false)
        for (calls in listOf(JSONObject.NULL, JSONArray())) {
            val candidate = JSONObject(plain.toString()).put("tool_calls", calls)
                .put("reasoning_content", JSONObject.NULL).put("tool_call_id", JSONObject.NULL)
            assertEquals(expected, AgentRequestTokenEstimate.boundary(JSONArray().put(candidate), JSONArray(), false, false))
        }
    }

    @Test fun oversizedPersistedFilesAreNotSentOrCounted() {
        val cases = listOf(
            Triple("image_file", io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES, true),
            Triple("video_file", io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES, false),
        )
        for ((type, maximum, vision) in cases) {
            val file = java.io.File.createTempFile("eta-budget-cap", ".bin")
            try {
                java.io.RandomAccessFile(file, "rw").use { it.setLength(maximum.toLong() + 1) }
                val source = JSONArray().put(message(JSONObject().put("type", type).put("path", file.absolutePath)))
                val boundary = AgentRequestTokenEstimate.boundary(source, JSONArray(), vision, !vision)
                // Hydration rejects the oversized file before reading its contents.
                val actual = AgentRequestMediaPolicy.filter(source, vision, !vision)
                assertEquals(3, boundary)
                assertEquals(boundary, AgentRequestTokenEstimate.filtered(actual, JSONArray()))
            } finally { file.delete() }
        }
    }

    @Test fun persistedImageUsesBoundedFileSizeWithoutDecoding() {
        val file = java.io.File.createTempFile("eta-budget-size", ".bin")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(4L * 1024 * 1024) }
            val source = JSONArray().put(message(JSONObject().put("type", "image_file").put("path", file.absolutePath)))
            assertEquals(3 + 1024, AgentRequestTokenEstimate.boundary(source, JSONArray(), true, false))
        } finally { file.delete() }
    }

    @Test fun persistedPathListingBeforeInlineMediaMatchesActualFilter() {
        val source = JSONArray().put(message(video(), text("a"),
            JSONObject().put("type", "image_file").put("path", "/missing/precision.png")))
        val original = source.toString()
        assertEquals(AgentRequestTokenEstimate.filtered(AgentRequestMediaPolicy.filter(source, false, true), JSONArray()),
            AgentRequestTokenEstimate.boundary(source, JSONArray(), false, true))
        assertEquals(original, source.toString())
    }

    @Test fun fixedOverheadIsIndependentOfUnsentRawHistoryMedia() {
        val source = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
            .put(message(video()))
        val expected = AgentContextBudget.countMessage(AgentModelClient.ConversationMessage("system", "rules"))
        assertEquals(expected, AgentRequestTokenEstimate.fixed(source, 1, JSONArray()))
    }

    @Test fun cloudCalibrationUsesOneStableBoundaryBasis() {
        val source = JSONArray().put(message(image(), video()))
        val boundary = AgentRequestTokenEstimate.boundary(source, JSONArray(), false, false)
        val budget = AgentSilentContextBudget()
        budget.requestStarted(boundary)
        budget.measured(5000)
        assertEquals(5000, budget.tokens(AgentRequestTokenEstimate.boundary(source, JSONArray(), false, false)))
        source.put(AgentConversationCodec.userTextMessage("new"))
        val added = AgentRequestTokenEstimate.boundary(JSONArray().put(AgentConversationCodec.userTextMessage("new")), JSONArray(), false, false)
        assertEquals(5000 + added, budget.tokens(AgentRequestTokenEstimate.boundary(source, JSONArray(), false, false)))
    }

}
