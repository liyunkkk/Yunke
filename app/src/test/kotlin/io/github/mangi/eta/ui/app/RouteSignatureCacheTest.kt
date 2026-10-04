package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.RequestOverheadCalibration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RouteSignatureCacheTest {
    private val model = Model("m", "model", "Model", contextWindow = 100000, createdAt = 0)
    private val provider = OpenAiCompatibleProviderSetting("p", "Test", "https://example.org/v1",
        models = listOf(model), createdAt = 0)

    @Test fun repeatedStrictAndContextShareOneDigestAndSameSnapshotDoesNotInvalidate() {
        val strictCalls = AtomicInteger()
        val cache = RouteSignatureCache(computeStrict = { p, m ->
            strictCalls.incrementAndGet()
            RequestOverheadCalibration.routeSignature(p, m).takeIf { it.isNotBlank() }
        })
        assertTrue(cache.updateProviders(listOf(provider)))
        val expected = RequestOverheadCalibration.routeSignature(provider, model)
        repeat(20) {
            assertEquals(expected, cache.context("p", "m") { _, _ -> error("strict route has no fallback") })
            assertEquals(expected, cache.strict("p", "m"))
        }
        assertFalse(cache.updateProviders(listOf(provider.copy(models = listOf(model.copy())))))
        assertEquals(expected, cache.strict("p", "m"))
        assertEquals(1, strictCalls.get())
    }

    @Test fun completeProviderAndModelConfigurationChangesAndRevertsInvalidateEvenNull() {
        val calls = AtomicInteger()
        val cache = RouteSignatureCache(computeStrict = { p, m ->
            calls.incrementAndGet()
            RequestOverheadCalibration.routeSignature(p, m).takeIf { it.isNotBlank() }
        })
        val changes = listOf(
            provider.copy(baseUrl = "https://other.example/v1"),
            provider.copy(systemPrompt = "other"), provider.copy(apiKey = "test-only-key"),
            provider.copy(authMode = "other"), provider.copy(endpointMode = "responses"),
            provider.copy(responsesStripReasoningStatus = true), provider.copy(hostedWebSearchEnabled = true),
            provider.copy(customHeaders = listOf(CustomHeader("x-test", "one"))),
            provider.copy(customBody = listOf(CustomBody("test", JsonPrimitive(1)))),
            provider.copy(sessionGatewayJson = "{}"),
            provider.copy(models = listOf(model.copy(contextWindowOverride = 200000))),
            provider.copy(models = listOf(model.copy(modelId = "other-model"))),
            provider.copy(models = listOf(model.copy(preferredReasoningEffort = ReasoningEffort.OFF))),
            provider.copy(models = listOf(model.copy(reasoningCapabilities = ModelReasoningCapabilities(mandatory = true)))),
            provider.copy(models = listOf(model.copy(customHeaders = listOf(CustomHeader("x-model", "one"))))),
            provider.copy(models = listOf(model.copy(customBody = listOf(CustomBody("test", JsonPrimitive(2)))))),
        )
        for (changed in changes) {
            cache.updateProviders(listOf(provider))
            assertEquals(RequestOverheadCalibration.routeSignature(provider, model), cache.strict("p", "m"))
            val before = calls.get()
            cache.updateProviders(listOf(changed))
            val expected = RequestOverheadCalibration.routeSignature(changed, changed.models.single()).takeIf { it.isNotBlank() }
            repeat(3) { assertEquals(expected, cache.strict("p", "m")) }
            cache.updateProviders(listOf(provider))
            assertEquals(RequestOverheadCalibration.routeSignature(provider, model), cache.strict("p", "m"))
            assertEquals(before + 2, calls.get())
        }
        for (unavailable in listOf(emptyList(), listOf(provider.copy(isEnabled = false)),
            listOf(provider.copy(models = emptyList())), listOf(provider.copy(models = listOf(model.copy(isEnabled = false)))))) {
            assertTrue(cache.updateProviders(unavailable))
            assertNull(cache.strict("p", "m"))
            assertNull(cache.context("p", "m") { _, _ -> error("unavailable route must stay null") })
            assertTrue(cache.updateProviders(listOf(provider)))
            assertNotNull(cache.strict("p", "m"))
        }
    }

    @Test fun contextFallbackAndStrictNullAreBothCachedAndActualChangesAreNotMissed() {
        val strictCalls = AtomicInteger()
        val actualCalls = AtomicInteger()
        val cache = RouteSignatureCache(computeStrict = { _, _ -> strictCalls.incrementAndGet(); null })
        cache.updateProviders(listOf(provider))
        repeat(10) {
            assertNull(cache.strict("p", "m"))
            assertEquals("actual:test-only-key", cache.context("p", "m") { p, _ ->
                actualCalls.incrementAndGet(); "actual:${p.apiKey.ifEmpty { "test-only-key" }}"
            })
        }
        assertEquals(1, strictCalls.get())
        assertEquals(1, actualCalls.get())
        cache.updateProviders(listOf(provider.copy(apiKey = "changed-test-only-key")))
        assertEquals("actual:changed-test-only-key", cache.context("p", "m") { p, _ ->
            actualCalls.incrementAndGet(); "actual:${p.apiKey}"
        })
        assertEquals(2, strictCalls.get())
        assertEquals(2, actualCalls.get())
    }

    @Test fun copiedNestedConfigurationCannotBeMutatedBehindTheCache() {
        val values = linkedMapOf("route" to JsonPrimitive("one"))
        val headers = mutableListOf(CustomHeader("x-test", "one"))
        val mutableProvider = provider.copy(customHeaders = headers,
            customBody = listOf(CustomBody("test", JsonObject(values))))
        val cache = RouteSignatureCache()
        cache.updateProviders(listOf(mutableProvider))
        headers[0] = CustomHeader("x-test", "two")
        values["route"] = JsonPrimitive("two")
        assertEquals("one:one", cache.context("p", "m") { p, _ ->
            p.customHeaders.single().value + ":" + (p.customBody.single().value as JsonObject)["route"]!!.let {
                (it as JsonPrimitive).content
            }
        })
        assertTrue(cache.updateProviders(listOf(mutableProvider)))
        assertEquals("two:two", cache.context("p", "m") { p, _ ->
            p.customHeaders.single().value + ":" + (p.customBody.single().value as JsonObject)["route"]!!.let {
                (it as JsonPrimitive).content
            }
        })
    }

    @Test fun jsonMemberOrderChangesInvalidateTheExistingToStringBasedAlgorithm() {
        val first = JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
        val second = JsonObject(linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1)))
        assertEquals(first, second)
        assertNotEquals(first.toString(), second.toString())
        val cache = RouteSignatureCache()
        cache.updateProviders(listOf(provider.copy(customBody = listOf(CustomBody("test", first)))))
        assertEquals(first.toString(), cache.context("p", "m") { p, _ -> p.customBody.single().value.toString() })
        assertTrue(cache.updateProviders(listOf(provider.copy(customBody = listOf(CustomBody("test", second))))))
        assertEquals(second.toString(), cache.context("p", "m") { p, _ -> p.customBody.single().value.toString() })
    }

    @Test fun concurrentNullIsSingleFlightAndMissingNullsHaveBoundedLruCapacity() {
        val calls = AtomicInteger()
        val misses = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = RouteSignatureCache(capacity = 2, computeStrict = { _, _ ->
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)); null
        }, onMiss = { misses.incrementAndGet() })
        cache.updateProviders(listOf(provider))
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 6).map { pool.submit<String?> { cache.strict("p", "m") } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            futures.forEach { assertNull(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
            assertEquals(1, misses.get())
        } finally { release.countDown(); pool.shutdownNow() }
        // Null entries consume capacity too, and a hit makes an entry most-recently used.
        cache.updateProviders(emptyList())
        val before = misses.get()
        assertNull(cache.strict("missing-1", "m"))
        assertNull(cache.strict("missing-2", "m"))
        assertNull(cache.strict("missing-1", "m"))
        assertNull(cache.strict("missing-3", "m"))
        assertNull(cache.strict("missing-2", "m"))
        assertEquals(before + 4, misses.get())
    }

    @Test fun lateOldGenerationResultRetriesInsteadOfPollutingNewConfiguration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val cache = RouteSignatureCache(computeStrict = { p, _ ->
            calls.incrementAndGet()
            if (p.systemPrompt == "old") {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); null
            } else "new-signature"
        })
        cache.updateProviders(listOf(provider.copy(systemPrompt = "old")))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val oldRead = pool.submit<String?> { cache.strict("p", "m") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(cache.updateProviders(listOf(provider.copy(systemPrompt = "new"))))
            assertEquals("new-signature", cache.strict("p", "m"))
            release.countDown()
            assertEquals("new-signature", oldRead.get(5, TimeUnit.SECONDS))
            assertEquals("new-signature", cache.strict("p", "m"))
            assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
