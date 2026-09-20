package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import org.junit.Assert.*
import org.junit.Test

class UserMessageFileReferencesTest {
    private fun file(path: String) = AgentFileReference(path.substringAfterLast('/'), path, AgentFileReferenceKind.File)

    @Test fun imagePreviewHidesOnlyItsDuplicateFileCard() {
        val photo = file("/cache/c1/photo.jpg")
        val document = file("/docs/readme.pdf")
        val message = UserMessageUi("u", "图片", images = listOf("data:image/jpeg;base64,preview"), imageSources = listOf(photo.absolutePath))
        assertEquals(listOf(document), message.visibleFileReferences(listOf(photo, document)))
    }

    @Test fun videoUsesOriginalSourceNotItsCoverForDeduplication() {
        val video = file("/cache/c1/clip.mp4")
        val cover = file("/cache/c1/cover.jpg")
        val message = UserMessageUi("u", "视频", images = listOf(cover.absolutePath), imageSources = listOf(video.absolutePath), imageIsVideo = listOf(true))
        assertEquals(listOf(cover), message.visibleFileReferences(listOf(video, cover)))
        assertTrue(message.isVideoAt(0))
        assertEquals(video.absolutePath, message.fullImageSourceAt(0))
    }

    @Test fun legacyPreviewPathWorksWithoutExplicitSource() {
        val photo = file("/cache/c1/photo.jpg")
        val message = UserMessageUi("u", "", images = listOf(photo.absolutePath))
        assertTrue(message.visibleFileReferences(listOf(photo)).isEmpty())
    }

    @Test fun filenameAloneNeverHidesUnrelatedFile() {
        val photo = file("/cache/c1/photo.jpg")
        val other = file("/downloads/photo.jpg")
        val message = UserMessageUi("u", "", images = listOf(photo.absolutePath))
        assertEquals(listOf(other), message.visibleFileReferences(listOf(photo, other)))
    }

    @Test fun filesRemainWhenNoMatchingMediaPreviewExists() {
        val photo = file("/cache/c1/photo.jpg")
        assertEquals(listOf(photo), UserMessageUi("u", "", imageSources = listOf(photo.absolutePath)).visibleFileReferences(listOf(photo)))
        assertEquals(listOf(photo), UserMessageUi("u", "", images = listOf("data:image/jpeg;base64,preview")).visibleFileReferences(listOf(photo)))
        assertEquals(listOf(photo), UserMessageUi("u", "", images = listOf("")).visibleFileReferences(listOf(photo)))
    }

    @Test fun fileUriAndAbsolutePathMatchWithoutFilenameGuessing() {
        val photo = file("/cache/c1/a b+图.jpg")
        val message = UserMessageUi("u", "", images = listOf("file:///cache/c1/a%20b+%E5%9B%BE.jpg"))
        assertTrue(message.visibleFileReferences(listOf(photo)).isEmpty())
        assertEquals(listOf(photo), message.copy(images = listOf("file://remote/cache/c1/a%20b+%E5%9B%BE.jpg")).visibleFileReferences(listOf(photo)))
    }

    @Test fun directoryReferencesAreNeverHidden() {
        val directory = file("/cache/c1/photo.jpg").copy(kind = AgentFileReferenceKind.Directory)
        val message = UserMessageUi("u", "", images = listOf(directory.absolutePath))
        assertEquals(listOf(directory), message.visibleFileReferences(listOf(directory)))
    }

    @Test fun supplementalMessageAndReloadKeepBackendReferencesUntouched() {
        val photo = file("/cache/c1/photo.jpg")
        val video = file("/cache/c1/clip.mp4")
        val document = file("/docs/readme.pdf")
        val references = listOf(photo, video, document)
        val content = AgentFileReferencePromptCodec.format("补充附件", references)
        val encoded = encodeUserMessageImages(
            listOf(photo.absolutePath, "/cache/c1/cover.jpg"),
            listOf(photo.absolutePath, video.absolutePath), listOf(false, true), listOf(null, 12000L))
        val decoded = decodeUserMessageImages(encoded)
        val message = UserMessageUi("u", content, images = decoded.previews, imageSources = decoded.sources, imageIsVideo = decoded.videoFlags, imageDurationsMs = decoded.durationsMs)
        assertEquals(listOf(document), message.visibleFileReferences(AgentFileReferencePromptCodec.parse(content).references))
        assertEquals(content, message.content)
        assertEquals(references, AgentFileReferencePromptCodec.parse(message.content).references)
        assertEquals(video.absolutePath, message.fullImageSourceAt(1))
        assertEquals(12000L, message.durationMsAt(1))
    }
}
