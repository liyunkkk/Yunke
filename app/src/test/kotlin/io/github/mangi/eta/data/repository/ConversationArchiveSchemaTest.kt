package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationArchiveSchemaTest {
    private val json = Json { explicitNulls = false }
    private val conversation = ConversationEntity(
        id = "source-id", title = "test", thinkingEnabled = false, createdAt = 1L, updatedAt = 1L,
    )

    @Test fun versionThreePreservesOwnerAndSourceIdentityAcrossMediaTransform() {
        val archive = EtaConversationExport(exportedAt = 1L, conversation = conversation,
            subAgentConfigJson = "{\"version\":1,\"enabled\":true}")
        assertEquals(3, EtaConversationExport.SCHEMA_VERSION)
        val parsed = json.decodeFromString<EtaConversationExport>(json.encodeToString(archive))
        val transformed = ConversationArchiveMedia.transform(parsed) { it }
        assertEquals("source-id", transformed.conversation.id)
        assertEquals(3, transformed.schemaVersion)
        assertEquals(archive.subAgentConfigJson, transformed.subAgentConfigJson)
    }

    @Test fun oldArchiveWithoutConfigDecodesAsAbsentNotCurrentOwner() {
        val old = EtaConversationExport(schemaVersion = 2, exportedAt = 1L,
            conversation = conversation, subAgentConfigJson = null)
        val raw = json.encodeToString(old)
        val parsed = json.decodeFromString<EtaConversationExport>(raw)
        assertEquals(2, parsed.schemaVersion)
        assertNull(parsed.subAgentConfigJson)
    }
}
