package io.github.mangi.eta.ui.model

import java.lang.ref.WeakReference
import java.util.RandomAccess

/**
 * A private, immutable, chunked snapshot. Replacing a payload copies one small
 * chunk and the chunk directory, not every historical message. Neither callers
 * nor older UI snapshots can mutate the backing chunks.
 *
 * The single-slot hint is valid only against the exact previous snapshot. Its
 * weak reference deliberately cannot retain a chain of old streaming versions.
 */
internal class AgentIncrementalList<T> private constructor(
    private val chunks: List<List<T>>,
    override val size: Int,
    private val previous: WeakReference<AgentIncrementalList<T>>? = null,
    private val changedIndex: Int = -1,
) : AbstractList<T>(), RandomAccess {
    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
        return chunks[index / CHUNK_SIZE][index % CHUNK_SIZE]
    }

    fun replacing(index: Int, value: T): AgentIncrementalList<T> {
        val old = get(index)
        if (old === value) return this
        val updatedChunks = chunks.toMutableList()
        val chunkIndex = index / CHUNK_SIZE
        updatedChunks[chunkIndex] = chunks[chunkIndex].toMutableList().also {
            it[index % CHUNK_SIZE] = value
        }
        return AgentIncrementalList(updatedChunks, size, WeakReference(this), index)
    }

    /** Terminal payloads retain the original scan/fallback routing, not streaming certificates. */
    fun withoutReplacementHint(): AgentIncrementalList<T> =
        if (previous == null) this else AgentIncrementalList(chunks, size)

    fun singleReplacementFrom(source: List<*>): Int? =
        changedIndex.takeIf { it >= 0 && previous?.get() === source }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentIncrementalList<*>) return super.equals(other)
        if (size != other.size) return false
        // Shared chunks are identical snapshots; only changed chunks need an
        // element comparison. Keep standard List equality for ordinary Lists.
        return chunks.indices.all { chunks[it] === other.chunks[it] || chunks[it] == other.chunks[it] }
    }

    override fun hashCode(): Int = super.hashCode()

    companion object {
        private const val CHUNK_SIZE = 32

        fun <T> copyOf(source: List<T>): AgentIncrementalList<T> {
            if (source is AgentIncrementalList<T>) return source
            return AgentIncrementalList(source.chunked(CHUNK_SIZE), source.size)
        }
    }
}

internal fun <T> List<T>.incrementalSnapshot(): AgentIncrementalList<T> = AgentIncrementalList.copyOf(this)

/**
 * Evaluate a payload transform in original order, retaining immutable snapshots
 * when semantic values do not change. Only an EXACT one-slot replacement earns
 * a chunk copy; terminal transforms intentionally keep the original unhinted
 * scan/fallback routing, and a multi-slot change takes the safe full copy.
 */
internal inline fun <T> List<T>.mapPreservingSnapshot(transform: (T) -> T): List<T> {
    var changedIndex = -1
    var changedValue: T? = null
    var multiple: MutableList<T>? = null
    for (index in indices) {
        val old = this[index]
        val current = transform(old)
        if (current === old || current == old) continue
        if (changedIndex < 0) {
            changedIndex = index
            changedValue = current
        } else {
            val copy = multiple ?: toMutableList().also {
                @Suppress("UNCHECKED_CAST")
                it[changedIndex] = changedValue as T
                multiple = it
            }
            copy[index] = current
        }
    }
    multiple?.let { return it.incrementalSnapshot() }
    if (changedIndex < 0) return this
    @Suppress("UNCHECKED_CAST")
    return incrementalSnapshot().replacing(changedIndex, changedValue as T).withoutReplacementHint()
}
