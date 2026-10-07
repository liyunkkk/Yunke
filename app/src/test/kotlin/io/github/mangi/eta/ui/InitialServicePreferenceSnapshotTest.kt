package io.github.mangi.eta.ui

import org.junit.Assert.*
import org.junit.Test

class InitialServicePreferenceSnapshotTest {
    private data class Service(val name: String)

    @Test fun successfulImmediateReadIsReusedExactlyOnce() {
        val service = Service("framework")
        val value = Any()
        val refreshed = Any()
        var calls = 0
        val snapshot = InitialServicePreferenceSnapshot(service, value)
        assertSame(value, snapshot.initialValueForUi())
        assertSame(value, snapshot.resolve(service) { calls++; refreshed })
        assertNull(snapshot.initialValueForUi())
        assertEquals(0, calls)
        assertSame(refreshed, snapshot.resolve(service) { calls++; refreshed })
        assertEquals(1, calls)
    }

    @Test fun failedInitialReadRetainsTheImmediateRetry() {
        val service = Service("framework")
        val recovered = Any()
        var calls = 0
        val snapshot = InitialServicePreferenceSnapshot<Service, Any>(service, null)
        assertSame(recovered, snapshot.resolve(service) { calls++; recovered })
        assertEquals(1, calls)
    }

    @Test fun replacementWithEqualValueButDifferentIdentityRefreshes() {
        val first = Service("framework")
        val replacement = Service("framework")
        val stale = Any()
        val fresh = Any()
        var calls = 0
        val snapshot = InitialServicePreferenceSnapshot(first, stale)
        assertSame(fresh, snapshot.resolve(replacement) { actual ->
            assertSame(replacement, actual)
            calls++
            fresh
        })
        assertEquals(1, calls)
    }

    @Test fun laterBindOrDeathNeverReusesTheInitialPreferences() {
        val service = Service("framework")
        val snapshot = InitialServicePreferenceSnapshot(service, Any())
        snapshot.resolve(service) { fail("must reuse successful immediate read"); null }
        var calls = 0
        assertNull(snapshot.resolve(null) { actual -> assertNull(actual); calls++; null })
        val fresh = Any()
        assertSame(fresh, snapshot.resolve(service) { calls++; fresh })
        assertEquals(2, calls)
    }

    @Test fun changedFirstNotificationConsumesTheCacheEvenIfServiceReturns() {
        val initial = Service("initial")
        val replacement = Service("replacement")
        val snapshot = InitialServicePreferenceSnapshot(initial, Any())
        val fresh = Any()
        var calls = 0
        snapshot.resolve(replacement) { calls++; fresh }
        assertSame(fresh, snapshot.resolve(initial) { calls++; fresh })
        assertEquals(2, calls)
    }

    @Test fun missingServiceStillUsesTheOriginalRefreshFallback() {
        val snapshot = InitialServicePreferenceSnapshot<Service, Any>(null, null)
        var calls = 0
        assertNull(snapshot.resolve(null) { actual -> assertNull(actual); calls++; null })
        assertEquals(1, calls)
    }
}
