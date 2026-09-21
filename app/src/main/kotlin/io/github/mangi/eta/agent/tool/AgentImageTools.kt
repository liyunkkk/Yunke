package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.json.JSONObject

/** 读取用户已明确指定的图片或视频；视频抽取封面帧作为视觉附件。 */
internal class AgentImageTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val resolveGuestPath: (String) -> String = { LinuxGuestPathResolver.resolveForApp(context, it) },
    private val chatImages: AgentChatImageCache = AgentChatImageCache(context),
) {
    fun readImage(args: JSONObject): AgentModelClient.ToolResult {
        val requested = args.getString("path").removePrefix("file://")
        val mapped = resolveGuestPath(requested)
        val source = resolveExistingImagePath(mapped, requested)
        val sourceKind = when {
            source.startsWith("content://") -> ImageSourceKind.ContentUri
            source.startsWith("/") && !source.contains('\u0000') -> ImageSourceKind.File
            else -> return sensitive(error("IMAGE_PATH_DENIED", "图片或视频路径必须是绝对路径、file URI 或已授权的 content URI"))
        }
        val looksVideo = looksLikeVideo(source, sourceKind)
        val maxBytes = if (looksVideo) MAX_AGENT_VIDEO_BYTES.toLong() else MAX_AGENT_IMAGE_BYTES.toLong()
        val temporaryFile = runCatching {
            File.createTempFile("eta-read-image-", ".img", imageCacheDirectory())
        }.getOrElse {
            return sensitive(error("IMAGE_TEMPORARY_FILE_FAILED", "无法创建图片临时文件"))
        }
        return try {
            val staged = copyAsApp(source, sourceKind, temporaryFile, maxBytes)
            if (!staged) {
                if (!rootAvailable()) {
                    return sensitive(error("IMAGE_ACCESS_DENIED", "YUNKe 无法读取此文件；请先通过文件选择器导入或授予读取权限"))
                }
                val copyResult = root.execute(
                    imageCopyCommand(source, sourceKind, temporaryFile, maxBytes),
                    timeoutMillis = READ_TIMEOUT_MS,
                    maxOutputBytes = 8 * 1024,
                )
                if (!copyResult.ok) return sensitive(copyFailure(copyResult))
            }
            val sniffedVideo = AgentVideoCodec.sniffFile(temporaryFile) != null
            val video = AgentVideoCodec.previewFromFile(temporaryFile, source)
                ?.takeIf { looksVideo || sniffedVideo }
            if (video != null) {
                val payload = JSONObject()
                    .put("ok", true)
                    .put("tool", "read_image")
                    .put("path", source)
                    .put("kind", "video")
                    .put("mime", video.mimeType)
                    .put("duration_ms", video.durationMs)
                    .put("image_attached", true)
                    .put("note", "已抽取视频封面帧作为视觉输入")
                video.width?.let { payload.put("width", it) }
                video.height?.let { payload.put("height", it) }
                return sensitive(content = payload.toString(), images = listOf(video.thumbnail))
            }
            if (looksVideo || sniffedVideo) {
                return sensitive(error("VIDEO_FRAME_FAILED", "已找到视频，但无法抽取封面帧"))
            }
            val image = AgentImageCodec.fromToolFile(
                file = temporaryFile,
                source = "tool_read_image",
            ) ?: return sensitive(error("IMAGE_UNSUPPORTED", "文件不是可识别的图片或视频"))
            sensitive(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "read_image")
                    .put("path", source)
                    .put("kind", "image")
                    .put("image_attached", true)
                    .toString(),
                images = listOf(image),
            )
        } catch (_: BoundedFileCopy.TooLargeException) {
            sensitive(error("IMAGE_TOO_LARGE", "文件超过大小限制"))
        } finally {
            temporaryFile.delete()
        }
    }

    private fun resolveExistingImagePath(mapped: String, requested: String): String {
        if (isReadableLocalFile(mapped)) return mapped
        chatImages.resolveReadableFile(mapped)?.let { return it.absolutePath }
        if (requested != mapped) {
            chatImages.resolveReadableFile(requested)?.let { return it.absolutePath }
        }
        return mapped
    }

    private fun isReadableLocalFile(path: String): Boolean {
        if (!path.startsWith("/") || path.contains('\u0000')) return false
        val file = File(path)
        return file.isFile && file.canRead()
    }

    private fun looksLikeVideo(source: String, sourceKind: ImageSourceKind): Boolean {
        if (AgentVideoCodec.isVideoSource(source)) return true
        if (sourceKind != ImageSourceKind.ContentUri) return false
        val mime = runCatching { context.contentResolver.getType(Uri.parse(source)) }.getOrNull().orEmpty()
        return AgentVideoCodec.isVideoMime(mime)
    }

    private fun copyAsApp(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
        maxBytes: Long,
    ): Boolean = try {
        val input = when (sourceKind) {
            ImageSourceKind.File -> File(source).takeIf { it.isFile && it.canRead() }?.inputStream()
            ImageSourceKind.ContentUri -> context.contentResolver.openInputStream(Uri.parse(source))
        }
        if (input == null) {
            false
        } else {
            input.use { sourceStream ->
                destination.outputStream().use { target ->
                    BoundedFileCopy.copy(sourceStream, target, maxBytes)
                }
            }
            true
        }
    } catch (tooLarge: BoundedFileCopy.TooLargeException) {
        throw tooLarge
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun imageCopyCommand(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
        maxBytes: Long,
    ): String = when (sourceKind) {
        ImageSourceKind.File -> "[ -f ${shellQuote(source)} ] || exit 21; " +
            "[ \"$(stat -c %s ${shellQuote(source)})\" -le $maxBytes ] || exit 22; " +
            "cp ${shellQuote(source)} ${shellQuote(destination.absolutePath)} || exit 23"
        ImageSourceKind.ContentUri -> "content read --uri ${shellQuote(source)} 2>/dev/null | " +
            "head -c ${maxBytes + 1L} > ${shellQuote(destination.absolutePath)} && " +
            "[ \"$(stat -c %s ${shellQuote(destination.absolutePath)})\" -le $maxBytes ]"
    }

    private fun imageCacheDirectory(): File =
        context.externalCacheDir
            ?.takeIf { it.isDirectory || it.mkdirs() }
            ?: context.cacheDir

    private fun copyFailure(result: BoundedRootCommandExecutor.Result): String = when (result.exitCode) {
        21 -> error("IMAGE_SOURCE_UNAVAILABLE", "图片源文件不存在或当前不可读")
        22 -> error("IMAGE_TOO_LARGE", "文件超过大小限制")
        23 -> error("IMAGE_STAGE_FAILED", "Root 无法将文件复制到 YUNKe 临时缓存")
        else -> error("IMAGE_UNAVAILABLE", "图片或视频读取失败")
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(
        content: String,
        images: List<AgentModelClient.ModelImage> = emptyList(),
    ) = AgentModelClient.ToolResult(content = content, images = images, sensitive = true)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private enum class ImageSourceKind {
        File,
        ContentUri,
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000L
    }
}
