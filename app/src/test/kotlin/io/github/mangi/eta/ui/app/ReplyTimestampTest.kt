package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.components.formatMessageTimestamp
import io.github.mangi.eta.data.model.AppearanceSettings
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReplyTimestampTest {
    @Test fun stampsOnlyFinalReplyAndKeepsRecordedTimeOnReplay() {
        val original = listOf(UserMessageUi("user-run-a", "question"),
            AgentMessageUi("assistant-run-a-1", "progress"),
            AgentMessageUi("assistant-run-a-2", "answer"))
        val stamped = stampCompletedReply(original, "run-a", 1000L)
        assertNull((stamped[1] as AgentMessageUi).generatedAtMillis)
        assertEquals(1000L, (stamped[2] as AgentMessageUi).generatedAtMillis)
        assertEquals(stamped, stampCompletedReply(stamped, "run-a", 2000L))
    }

    @Test fun missingLegacyTimeOrEmptyReplyDoesNotInventTimestamp() {
        val messages = listOf(AgentMessageUi("assistant-run-a-1", "answer"))
        assertEquals(messages, stampCompletedReply(messages, "run-a", null))
        assertEquals(messages, stampCompletedReply(messages, "run-a", 0L))
        assertEquals(messages, stampCompletedReply(messages, "run-other", 1000L))
        val empty = listOf(AgentMessageUi("assistant-run-a-1", ""))
        assertEquals(empty, stampCompletedReply(empty, "run-a", 1000L))
    }

    @Test fun regeneratingNewRunDoesNotChangeOldReplyTimestamp() {
        val messages = listOf(AgentMessageUi("assistant-run-a-1", "old", generatedAtMillis = 1000L),
            AgentMessageUi("assistant-run-b-1", "new"))
        val stamped = stampCompletedReply(messages, "run-b", 3000L)
        assertEquals(1000L, stamped.filterIsInstance<AgentMessageUi>()[0].generatedAtMillis)
        assertEquals(3000L, stamped.filterIsInstance<AgentMessageUi>()[1].generatedAtMillis)
    }

    @Test fun formatsRecordedInstantInSelectedZoneWithoutUsingCurrentTime() {
        val time = Instant.parse("2026-09-20T08:37:00Z").toEpochMilli()
        assertEquals("2026-09-20 16:37", formatMessageTimestamp(time, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-09-20 08:37", formatMessageTimestamp(time, ZoneId.of("UTC")))
        assertFalse(AppearanceSettings().messageTimestampsEnabled)
    }
}
