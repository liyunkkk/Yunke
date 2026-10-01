package io.github.mangi.eta.ui.components

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * 已解码的聊天预览图。条目滚出再滚回、卡片收起再展开时直接复用，不再重新解码。
 * 按像素字节数限额，线程安全（[LruCache] 内部同步）。
 */
internal object ChatPreviewBitmapCache {
    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            runCatching { value.asAndroidBitmap().allocationByteCount }.getOrDefault(value.width * value.height * 4)
                .coerceAtLeast(1)
    }

    fun get(source: String): ImageBitmap? = if (source.isBlank()) null else cache.get(source)

    fun put(source: String, bitmap: ImageBitmap) {
        if (source.isNotBlank()) cache.put(source, bitmap)
    }
}
