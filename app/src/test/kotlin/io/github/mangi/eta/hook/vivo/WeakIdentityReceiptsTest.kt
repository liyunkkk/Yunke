package io.github.mangi.eta.hook.vivo

import org.junit.Assert.*
import org.junit.Test

class WeakIdentityReceiptsTest {
    private data class Payload(val value: String)
    @Test fun readingAReceiptDoesNotConsumeItsVendorFence() {
        val receipts = WeakIdentityReceipts<Any>()
        val payload = Payload("same")
        val receipt = Any()
        receipts.put(payload, receipt)
        assertSame(receipt, receipts.get(payload))
        assertSame(receipt, receipts.get(payload))
        receipts.put(payload, Any())
        assertSame(receipt, receipts.get(payload))
    }
    @Test fun equalButDistinctPayloadsAreNotConfused() {
        val receipts = WeakIdentityReceipts<Any>()
        val a = Payload("equal"); val b = Payload("equal")
        val first = Any(); val second = Any()
        receipts.put(a, first); receipts.put(b, second)
        assertSame(first, receipts.get(a))
        assertSame(second, receipts.get(b))
        assertNull(receipts.get(Payload("equal")))
    }
}
