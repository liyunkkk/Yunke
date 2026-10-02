package io.github.mangi.eta.hook.vivo

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** A claimed native object remains fenced while alive; no TTL-based vendor retransmission. */
internal class WeakIdentityReceipts<T : Any> {
    private class Key(value: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get() != null && get() === other.get()
    }
    private val queue = ReferenceQueue<Any>()
    private val values = HashMap<Key, T>()
    private fun clean() {
        while (true) { val key = queue.poll() as? Key ?: return; values.remove(key) }
    }
    @Synchronized fun put(key: Any, value: T) {
        clean()
        values.putIfAbsent(Key(key, queue), value)
    }
    @Synchronized fun get(key: Any): T? {
        clean()
        return values[Key(key, null)]
    }
}
