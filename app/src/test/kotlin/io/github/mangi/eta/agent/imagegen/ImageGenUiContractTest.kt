package io.github.mangi.eta.agent.imagegen

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.visibleOnCurrentDevice
import io.github.mangi.eta.ui.model.decodeUserMessageImages
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenUiContractTest {
    @Test
    fun imageGenerationCardOpensSettingsWithoutRootOrAccessibility() {
        val capabilities = AgentToolCapabilities(rootAvailable = false, accessibilityAvailable = false)
        assertTrue(visibleOnCurrentDevice(ImageGenToolCatalog.TOOL_NAME, rootGranted = false, colorOs = false))
        assertEquals(AgentToolsAction.OpenImageGenSettings, toolCardAction(ImageGenToolCatalog.TOOL_NAME, capabilities))
    }

    @Test
    fun commandPayloadRoundTripsImagesAndEditMetadata() {
        val payload = GeneratedImageActivityPayload(
            prompt = "调整背景色",
            aspectRatio = "16:9",
            resolution = "2K",
            requestedCount = 2,
            referenceCount = 1,
            images = listOf(
                GeneratedImageActivityPayload.GeneratedImageItem(
                    id = "generated-1", filePath = "/managed/generated-1.png", width = 2048, height = 1152,
                ),
            ),
        )
        assertEquals(payload, GeneratedImageActivityPayload.decode(payload.encode()))
        assertNull(GeneratedImageActivityPayload.decode("ordinary shell command"))
        assertNull(GeneratedImageActivityPayload.decode(null))
    }

    @Test
    fun persistedUserImageObjectsDecodeSourceAndMarkVideoAttachments() {
        val decoded = decodeUserMessageImages(
            """[{"preview":"thumb.jpg","source":"/private/cache/photo.jpg"},{"preview":"clip.jpg","source":"/private/cache/clip.mp4","kind":"video"}]""",
        )
        assertEquals("/private/cache/photo.jpg", decoded.sources[0])
        assertTrue(decoded.videoFlags[1])
    }

    @Test
    fun currentConversationAttachmentOwnershipRequiresCanonicalChildPath() {
        val cache = createTempDirectory("imagegen-cache-").toFile()
        val own = File(cache, "eta-chat-images/session-1/photo.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val other = File(cache, "eta-chat-images/session-2/photo.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        try {
            assertTrue(ImageGenToolExecutor.isCurrentConversationAttachment(cache, "session-1", own))
            assertFalse(ImageGenToolExecutor.isCurrentConversationAttachment(cache, "session-1", other))
            assertFalse(ImageGenToolExecutor.isCurrentConversationAttachment(cache, null, own))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun imageMetadataIndexRetainsMoreThanLegacyFiveHundredRecordWindow() {
        val records = (1..501).map { index ->
            GeneratedImageRecord(
                id = "img_$index",
                filePath = "/managed/img_$index.png",
                mimeType = "image/png",
                sessionKey = "session-$index",
            )
        }
        val merged = GeneratedImageStore.mergeIndexRecords(records, emptyList())
        assertEquals(501, merged.size)
        assertEquals("session-1", merged.last().sessionKey)
        assertEquals("session-501", merged.first().sessionKey)
        val restored = Json.decodeFromString<List<GeneratedImageRecord>>(Json.encodeToString(merged))
        assertEquals(merged, restored)
        val legacy = Json.decodeFromString<GeneratedImageRecord>(
            """{"id":"img_legacy","filePath":"/managed/legacy.png","mimeType":"image/png"}""",
        )
        assertNull(legacy.sessionKey)
    }

    @Test
    fun runningPayloadUsesReferenceCountWithoutRetainingReferencePaths() {
        val raw = """{"prompt":"编辑图片","count":9,"reference_images":["/private/reference.png"],"aspect_ratio":"1:1"}"""
        val payload = requireNotNull(GeneratedImageActivityPayload.initialFromArguments(raw))
        assertEquals(4, payload.requestedCount)
        assertEquals(1, payload.referenceCount)
        assertEquals("1:1", payload.aspectRatio)
        assertTrue(payload.images.isEmpty())
        assertFalse(payload.encode().contains("/private/reference.png"))
    }
}
