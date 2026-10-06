package io.github.mangi.eta.data.repository

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelUsageLedgerEquivalenceTest {
    private fun delta(
        request: String? = "request-a",
        conversation: String? = "a",
        input: Long = 120,
        at: Long = 2000,
    ) = ModelUsageDelta(
        providerId = "p", providerName = "Provider", modelId = "main", modelDisplayName = "Main",
        inputTokens = input, outputTokens = 12, cachedTokens = 80, cacheCreationTokens = 8,
        conversationId = conversation, round = 1, requestId = request, atMillis = at,
        day = LocalDate.of(1970, 1, 1),
    )

    @Test fun emptyMalformedLegacyAndInitializedInputsMatchOriginal() {
        val inputs = linkedMapOf(
            "null" to null,
            "empty" to "",
            "blank" to " \n\t",
            "malformed" to "not JSON",
            "truncated" to "{\"providers\":",
            "array" to "[1,2]",
            "empty object" to "{}",
            "legacy event-only ledger" to legacyRaw(),
            "legacy cumulative ledger" to legacyRaw(withCounters = true),
            "initialized ledger" to initializedRaw(),
            "initialized without totals" to JSONObject(legacyRaw())
                .put("conversationTotalsInitialized", true).toString(),
            "initialized with non-object totals" to JSONObject(legacyRaw())
                .put("conversationTotalsInitialized", true).put("conversationTotalsV1", "old").toString(),
            "string initialization flag" to JSONObject(initializedRaw())
                .put("conversationTotalsInitialized", "true").toString(),
            "non-object providers" to "{\"conversationTotalsInitialized\":true,\"providers\":7}",
            "non-object model fields" to """{
                "conversationTotalsInitialized": true,
                "conversationTotalsV1": {"a": null},
                "providers": {"p": {"models": {"main": {
                    "events": "invalid", "conversations": 1, "days": false,
                    "inputTokens": "25", "outputTokens": null
                }}}},
                "unrelated": {"array": [null, true, 1.25], "number": 1e4}
            }""",
        )
        val changes = listOf(
            delta(),
            delta(request = "new-request"),
            delta(request = null),
            delta(conversation = null),
            delta(conversation = " "),
            delta(input = -10).copy(outputTokens = -20, cachedTokens = -30, cacheCreationTokens = -40),
        )
        inputs.forEach { (name, raw) ->
            changes.forEachIndexed { index, change ->
                assertDeltaMatchesOriginal("$name / delta $index", raw, change)
            }
            assertSequenceMatchesOriginal("$name multi-edit batch", raw, changes)
        }
        // Invalid JSON must still bypass seeding completely, rather than initialize an empty ledger.
        val fallback = JSONObject(applyModelUsageDelta("not JSON", delta()))
        assertFalse(fallback.has("conversationTotalsInitialized"))
        assertEquals(120L, fallback.getJSONObject("conversationTotalsV1").getJSONObject("a").getLong("in"))
    }

    @Test fun progressiveRequestReplacementAndNewRequestAppendMatchOriginal() {
        assertSequenceMatchesOriginal(
            "progressive requests", initializedRaw(),
            listOf(
                delta(input = 150),
                delta(request = "request-b", input = 300, at = 3000),
                delta(input = 200, at = 4000),
                delta(input = 200, at = 4000),
                delta(request = "request-c", input = 500, at = 5000),
                delta(input = 90, at = 6000),
                delta(request = "child-request", input = 20).copy(modelId = "child", modelDisplayName = "Child"),
                delta(request = "other-provider", conversation = "b").copy(providerId = "new", providerName = "New"),
            ),
        )
    }

    @Test fun legacyRoundReplacementAndMissingConversationMatchOriginal() {
        assertSequenceMatchesOriginal(
            "legacy rounds", legacyRaw(),
            listOf(
                delta(request = null, input = 50),
                delta(request = "new-request", input = 200),
                delta(request = null, input = 70),
                delta(request = "anonymous", conversation = null, input = 80),
                delta(request = "anonymous", conversation = null, input = 90),
                delta(request = null, conversation = null, input = 100),
                delta(request = null, conversation = "", input = 110),
                delta(request = null, conversation = " ", input = 120),
            ),
        )
        val raw = assertDeltaMatchesOriginal("no owner", initializedRaw(), delta(conversation = null))
        assertJsonEquivalent(
            "conversation totals unchanged without owner",
            JSONObject(initializedRaw()).getJSONObject("conversationTotalsV1"),
            JSONObject(raw).getJSONObject("conversationTotalsV1"),
        )
    }

    @Test fun eventWindowBoundaryAndOversizedHistoryMatchOriginal() {
        // Build a large fixture once, not by repeatedly serializing an ever-growing ledger.
        for (count in listOf(3999, 4000, 4003)) {
            val root = JSONObject(initializedRaw())
            val model = root.getJSONObject("providers").getJSONObject("p")
                .getJSONObject("models").getJSONObject("main")
            val events = JSONArray()
            repeat(count) { index ->
                events.put(JSONObject().put("t", index + 1).put("in", 1).put("out", 0)
                    .put("c", "a").put("r", 1).put("q", "retained-$index"))
            }
            model.put("events", events).put("inputTokens", 10000L).put("outputTokens", 1000L)
                .put("cachedTokens", 500L).put("cacheCreationTokens", 100L)
            val raw = root.toString()
            val appended = assertDeltaMatchesOriginal(
                "append to $count events", raw, delta(request = "last", input = 200, at = 99999),
            )
            val result = JSONObject(appended).getJSONObject("providers").getJSONObject("p")
                .getJSONObject("models").getJSONObject("main")
            assertEquals(minOf(count + 1, 4000), result.getJSONArray("events").length())
            assertEquals(10200L, result.getLong("inputTokens"))
            assertDeltaMatchesOriginal("replace last of $count events", raw, delta(request = "retained-${count - 1}"))
            assertDeltaMatchesOriginal("replace first of $count events", raw, delta(request = "retained-0"))
            assertSequenceMatchesOriginal("batched trim/reappearance $count", raw, listOf(
                delta(request = "last", input = 200, at = 99999),
                delta(request = "retained-0", input = 1),
                delta(request = "last", input = 20, at = 99999),
            ))
        }
    }

    @Test fun noOpDeltasPreserveRawStringExactly() {
        val changes = listOf(
            delta().copy(providerId = " "),
            delta().copy(modelId = ""),
            delta(conversation = null, input = 0).copy(outputTokens = 0),
            delta(conversation = " ", input = -1).copy(outputTokens = -1),
        )
        for (raw in listOf(null, "", "invalid JSON", " { \"conversationTotalsInitialized\" : true } ", legacyRaw())) {
            changes.forEach { change ->
                assertEquals(originalApplyModelUsageDelta(raw, change), applyModelUsageDelta(raw, change))
                assertEquals(raw.orEmpty(), applyModelUsageDelta(raw, change))
                assertEquals(raw.orEmpty(), applyModelUsageDeltas(raw, listOf(change)))
            }
        }
    }

    @Test fun batchesPreserveDecodeEncodeNormalizationAndInvalidTimestampSemantics() {
        val raw = """{"conversationTotalsInitialized":true,"conversationTotalsV1":{},
            "providers":{"p":{"models":{"main":{"events":[
                {"t":1,"in":10,"out":1,"k":-3,"w":-1,"c":"a","q":"old"}
            ]}}}}}"""
        assertSequenceMatchesOriginal("cache normalization and timestamps", raw, listOf(
            delta(request = "new", input = 20),
            delta(request = "old", input = 5),
            delta(request = "bad-time", input = 3, at = 0),
            delta(request = "bad-time", input = 4, at = 10),
            delta(request = "", input = 2),
            delta(request = null, input = 1),
            delta(request = "changed-owner", conversation = "b", input = 3),
            delta(request = "changed-owner", conversation = "a", input = 1),
        ))
    }

    @Test fun seededRandomBatchesMatchFrozenOracleAcrossModelsAndRequests() {
        val random = java.util.Random(42)
        val changes = List(120) { index ->
            delta(request = "request-${random.nextInt(12)}", input = random.nextInt(900).toLong(),
                at = 1000L + index).copy(
                modelId = "model-${random.nextInt(3)}",
                conversationId = "owner-${random.nextInt(3)}",
                outputTokens = random.nextInt(30).toLong(),
                cachedTokens = random.nextInt(70).toLong(),
                cacheCreationTokens = random.nextInt(10).toLong(),
                day = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(10).toLong()),
            )
        }
        assertSequenceMatchesOriginal("seeded random partials", "{}", changes)
    }

    @Test fun seedStringWrapperRetainsOriginalMigrationAndFailureBehavior() {
        val legacy = mapOf(
            "a" to ConversationUsageTotals(1000, 5, 800, 1),
            "messages-only" to ConversationUsageTotals(50, 60, 20, 10),
        )
        val inputs = listOf(
            null, "", " \n", "not JSON", "{\"providers\":", "[1,2]", "{}",
            legacyRaw(), legacyRaw(withCounters = true), initializedRaw(),
            JSONObject(initializedRaw()).put("conversationTotalsInitialized", false).toString(),
            JSONObject(initializedRaw()).put("conversationTotalsInitialized", "true").toString(),
            JSONObject(legacyRaw()).put("conversationTotalsInitialized", true).removeTotals().toString(),
            JSONObject(legacyRaw()).put("conversationTotalsV1", JSONObject().put("a", JSONObject.NULL)).toString(),
        )
        inputs.forEachIndexed { index, raw ->
            for (baseline in listOf(emptyMap<String, ConversationUsageTotals>(), legacy)) {
                val expected = runCatching { originalSeedConversationUsage(raw, baseline) }
                val actual = runCatching { seedConversationUsage(raw, baseline) }
                if (expected.isFailure) {
                    assertTrue("seed input $index should throw", actual.isFailure)
                    assertEquals(expected.exceptionOrNull()!!.javaClass, actual.exceptionOrNull()!!.javaClass)
                } else {
                    assertTrue("seed input $index should succeed", actual.isSuccess)
                    assertJsonEquivalent("seed input $index", JSONObject(expected.getOrThrow()), JSONObject(actual.getOrThrow()))
                }
            }
        }
        val seeded = seedConversationUsage(legacyRaw(), legacy)
        // Per-field maxima, collapsed legacy rounds, multiple models, and existing totals all matter.
        assertEquals(ConversationUsageTotals(1000, 21, 800, 7), conversationUsageTotals(seeded, "a"))
        val reseeded = seedConversationUsage(seeded, mapOf("a" to ConversationUsageTotals(9999, 9999, 9999, 9999)))
        assertJsonEquivalent("existing totals are never overwritten", JSONObject(seeded), JSONObject(reseeded))
    }

    @Test fun inPlaceSeedingUsesOriginalRawSnapshotNotMutatedRoot() {
        val raw = legacyRaw()
        val parsed = JSONObject(raw)
        parsed.getJSONObject("providers").getJSONObject("p").getJSONObject("models")
            .getJSONObject("main").put("events", JSONArray())
        seedConversationUsageInPlace(parsed, raw, emptyMap())
        assertJsonEquivalent(
            "migration reads untouched raw events",
            JSONObject(originalSeedConversationUsage(raw, emptyMap())).getJSONObject("conversationTotalsV1"),
            parsed.getJSONObject("conversationTotalsV1"),
        )
        assertTrue(parsed.getBoolean("conversationTotalsInitialized"))
        val initialized = JSONObject(initializedRaw())
        val existingTotals = initialized.getJSONObject("conversationTotalsV1")
        seedConversationUsageInPlace(initialized, "invalid JSON", emptyMap())
        assertSame("initialized seed does not replace totals or decode raw", existingTotals,
            initialized.getJSONObject("conversationTotalsV1"))
        assertJsonEquivalent("initialized seed is a no-op", JSONObject(initializedRaw()), initialized)
    }

    @Test fun nonCanonicalNumbersInInitializedLedgerMatchOriginal() {
        // Hand-written raw text: these number forms must reach applyModelUsageDelta unnormalized.
        fun ledger(event: String) = """{"conversationTotalsInitialized":true,
            "conversationTotalsV1":{"a":{"in":10,"out":0,"k":0,"w":0}},
            "providers":{"p":{"name":"Provider","models":{"main":{"displayName":"Main",
            "inputTokens":10,"outputTokens":0,"cachedTokens":0,"cacheCreationTokens":0,
            "events":[$event],"conversations":["a"],"days":["1970-01-01"]}}}}}"""
        val cases = listOf(
            "numeric decimal request id" to ledger("""{"t":1000,"in":10,"out":0,"c":"a","r":1,"q":100.0}"""),
            "exponent request id" to ledger("""{"t":1000,"in":10,"out":0,"c":"a","r":1,"q":1E2}"""),
            "exponent round" to ledger("""{"t":1000,"in":10,"out":0,"c":"a","r":3e9}"""),
            "decimal counter" to ledger("""{"t":1000,"in":10.0,"out":0,"c":"a","r":1,"q":"100"}"""),
        )
        val deltas = listOf(delta(request = "100"), delta(request = null), delta(request = "other"))
        cases.forEach { (label, raw) ->
            assertFalse(label, JSONObject(raw).hasOnlyCanonicalJsonValues())
            assertRootReuse(label, raw, expectedReuse = false)
            deltas.forEachIndexed { index, change -> assertDeltaMatchesOriginal("$label delta $index", raw, change) }
        }
        // initializedRaw() deliberately carries a 1.25 field, so it covers the round-trip
        // fallback; the production-shaped ledger below must take the fast path.
        assertFalse(JSONObject(initializedRaw()).hasOnlyCanonicalJsonValues())
        // A raw Java null inside an array is not round-trip stable (it re-parses as NULL).
        assertFalse(JSONObject().put("days", JSONArray().put(null as Any?)).hasOnlyCanonicalJsonValues())
        assertTrue(JSONObject().put("days", JSONArray().put(JSONObject.NULL)).hasOnlyCanonicalJsonValues())
    }

    @Test fun collisionObjectChangesStringCoercionAcrossJvmRoundTrip() {
        // Keep the hand-written insertion order; serializing this fixture before apply would
        // hide the first parse/re-parse difference under review.
        val parsed = JSONObject("""{"q":$collisionObjectRaw}""")
        val keys = parsed.getJSONObject("q").keys().asSequence().toList()
        assertEquals(12, keys.size)
        assertEquals("all fixture keys collide in Java's HashMap", 1, keys.map { it.hashCode() }.toSet().size)
        assertTrue("the old recursive guard admits this object", parsed.hasOnlyCanonicalJsonValues())
        val reparsed = JSONObject(parsed.toString())
        assertNotEquals("rebuilding the object changes its optString result on JVM org.json",
            parsed.optString("q"), reparsed.optString("q"))
    }

    @Test fun containerStringCoercionsFallBackAndMatchOriginal() {
        // These raw inputs are never pre-normalized with JSONObject.toString(). An array can
        // also carry a colliding object, so even its optString conversion can change.
        for ((shape, value) in listOf(
            "object" to collisionObjectRaw,
            "array containing object" to """[$collisionObjectRaw,"tail"]""",
        )) {
            val cases = listOf(
                "q $shape" to stringCoercionRaw(eventFields = """ "c":"old","q":$value """),
                "c $shape" to stringCoercionRaw(eventFields = """ "c":$value,"q":"kept" """),
                "conversations element $shape" to stringCoercionRaw(conversations = """["old",$value]"""),
                "days element $shape" to stringCoercionRaw(days = """["1970-01-01",$value]"""),
            )
            for ((label, raw) in cases) {
                assertTrue("$label passed the old scalar guard", JSONObject(raw).hasOnlyCanonicalJsonValues())
                assertRootReuse(label, raw, expectedReuse = false)
                assertDeltaMatchesOriginal("$label append", raw, delta(request = "incoming", conversation = "new"))
                val originalRequest = JSONObject(originalSeedConversationUsage(raw, emptyMap()))
                    .getJSONObject("providers").getJSONObject("p").getJSONObject("models")
                    .getJSONObject("main").getJSONArray("events").getJSONObject(0).optString("q")
                assertDeltaMatchesOriginal("$label request replacement", raw,
                    delta(request = originalRequest, conversation = "new"))
                assertDeltaMatchesOriginal("$label no request", raw, delta(request = null, conversation = "new"))
            }
        }
    }

    @Test fun scalarStringCoercionsRemainFastAndJavaNullStillFallsBack() {
        val raw = stringCoercionRaw(
            eventFields = """ "q":7,"c":true """,
            conversations = """["old",7,true,null]""",
            days = """["1970-01-01",8,false,null]""",
        )
        assertRootReuse("stable scalar coercions", raw, expectedReuse = true)
        assertDeltaMatchesOriginal("stable scalar coercions", raw, delta(request = "7"))
        val root = JSONObject(canonicalInitializedRaw())
        root.getJSONObject("providers").getJSONObject("p").getJSONObject("models")
            .getJSONObject("main").getJSONArray("days").put(null as Any?)
        assertFalse(root.hasOnlyCanonicalJsonValues())
        assertNotSame("raw Java null retains the original round-trip", root,
            root.rootForModelUsageDelta("p", "main", wasInitialized = true))
    }

    @Test fun canonicalInitializedLedgerTakesFastPathAndMatchesOriginal() {
        val raw = canonicalInitializedRaw()
        assertTrue(JSONObject(raw).hasOnlyCanonicalJsonValues())
        assertRootReuse("production-shaped initialized ledger", raw, expectedReuse = true)
        assertRootReuse("migration keeps the old round-trip", legacyRaw(), expectedReuse = false)
        assertSequenceMatchesOriginal("canonical fast path", raw, listOf(
            delta(request = "request-a", input = 150),
            delta(request = "request-a", input = 170),
            delta(request = "request-new", input = 40, at = 3000),
            delta(request = null, input = 9),
            delta(request = "x", conversation = null, input = 3),
            delta(request = "esc", conversation = "q\"\\/\u0001\u00e9\ud83d\ude00", input = 4),
        ))
    }

    private fun JSONObject.removeTotals(): JSONObject = apply { remove("conversationTotalsV1") }

    private val collisionObjectRaw = """{
        "AaAaAaAa":1,"AaAaBBAa":1,"AaAaBBBB":1,"AaBBAaAa":1,
        "AaBBAaBB":1,"AaBBBBAa":1,"AaBBBBBB":1,"BBAaAaAa":1,
        "BBAaAaBB":1,"BBAaBBAa":1,"BBAaBBBB":1,"AaAaAaBB":1
    }"""

    private fun stringCoercionRaw(
        eventFields: String = """ "c":"old","q":"kept" """,
        conversations: String = """["old"]""",
        days: String = """["1970-01-01"]""",
    ): String = """{
        "conversationTotalsInitialized":true,
        "conversationTotalsV1":{"old":{"in":10,"out":0,"k":0,"w":0}},
        "providers":{"p":{"name":"Provider","models":{"main":{
            "displayName":"Main","inputTokens":10,"outputTokens":0,
            "cachedTokens":0,"cacheCreationTokens":0,
            "events":[{"t":1000,"in":10,"out":0,"r":1,$eventFields}],
            "conversations":$conversations,"days":$days
        }}}}
    }"""

    private fun assertRootReuse(label: String, raw: String, expectedReuse: Boolean) {
        val parsed = JSONObject(raw)
        val wasInitialized = parsed.optBoolean("conversationTotalsInitialized")
        seedConversationUsageInPlace(parsed, raw, emptyMap())
        val selected = parsed.rootForModelUsageDelta("p", "main", wasInitialized)
        if (expectedReuse) assertSame("$label must take the actual fast path", parsed, selected)
        else assertNotSame("$label must actually serialize/re-parse", parsed, selected)
        assertJsonEquivalent("$label prepared root",
            JSONObject(originalSeedConversationUsage(raw, emptyMap())), selected)
    }

    private fun legacyRaw(withCounters: Boolean = false): String {
        val main = JSONObject().put("displayName", "Legacy main")
            .put("events", JSONArray("""[
                {"t":1000,"in":20,"out":2,"k":3,"w":1,"c":"a","r":1},
                {"t":1100,"in":30,"out":3,"k":4,"w":2,"c":"a","r":1},
                {"t":1200,"in":100,"out":10,"k":70,"w":5,"c":"a","r":1,"q":"request-a"},
                {"t":1300,"in":80,"out":8,"k":6,"c":"b","q":"other-owner"},
                {"t":0,"in":999,"out":999,"c":"a"},
                "not an event"
            ]"""))
            .put("conversations", JSONArray("[\"a\",\"a\",\"b\",\"\",7]"))
            .put("days", JSONArray("[\"1970-01-01\",\"\"]"))
            .put("unrelatedModelField", JSONObject().put("flag", true))
        if (withCounters) {
            main.put("inputTokens", 5000L).put("outputTokens", 500L)
                .put("cachedTokens", 1000L).put("cacheCreationTokens", 50L)
        }
        val child = JSONObject().put("events", JSONArray("""[
            {"t":1400,"in":7,"out":8,"k":9,"c":"a","q":"child-request"}
        ]"""))
        val other = JSONObject().put("events", JSONArray("""[
            {"t":1500,"in":5,"out":6,"c":"b","q":"other-model"}
        ]"""))
        return JSONObject().put("providers", JSONObject()
            .put("p", JSONObject().put("name", "Old provider").put("models", JSONObject().put("main", main).put("child", child)))
            .put("z", JSONObject().put("models", JSONObject().put("aux", other))))
            .put("unrelatedRootField", JSONArray().put(JSONObject.NULL).put(true).put(1.25)).toString()
    }

    /** Same shape the app writes: integers, strings and booleans only. */
    private fun canonicalInitializedRaw(): String {
        val events = JSONArray()
        listOf(
            """{"t":1000,"in":20,"out":2,"k":3,"w":1,"c":"a","r":1}""",
            """{"t":1200,"in":100,"out":10,"k":70,"w":5,"c":"a","r":1,"q":"request-a"}""",
            """{"t":1300,"in":80,"out":8,"k":6,"c":"b","q":"other-owner"}""",
            """{"t":1400,"in":3000000000,"out":1,"c":"a","r":2,"q":"big"}""",
        ).forEach { events.put(JSONObject(it)) }
        val main = JSONObject().put("displayName", "Main").put("events", events)
            .put("inputTokens", 3000000200L).put("outputTokens", 21L)
            .put("cachedTokens", 79L).put("cacheCreationTokens", 6L)
            .put("conversations", JSONArray().put("a").put("b"))
            .put("days", JSONArray().put("1970-01-01"))
        return JSONObject()
            .put("conversationTotalsInitialized", true)
            .put("conversationTotalsV1", JSONObject()
                .put("a", JSONObject().put("in", 3000000120L).put("out", 13).put("k", 73).put("w", 6))
                .put("b", JSONObject().put("in", 80).put("out", 8).put("k", 6).put("w", 0)))
            .put("providers", JSONObject().put("p", JSONObject().put("name", "Provider")
                .put("models", JSONObject().put("main", main))))
            .toString()
    }

    private fun initializedRaw(): String = originalSeedConversationUsage(
        legacyRaw(withCounters = true), mapOf("a" to ConversationUsageTotals(1000, 100, 800, 20)),
    )

    private fun assertDeltaMatchesOriginal(label: String, raw: String?, delta: ModelUsageDelta): String {
        val expected = originalApplyModelUsageDelta(raw, delta)
        val actual = applyModelUsageDelta(raw, delta)
        assertJsonEquivalent(label, JSONObject(expected), JSONObject(actual))
        assertJsonEquivalent("$label batched", JSONObject(expected),
            JSONObject(applyModelUsageDeltas(raw, listOf(delta))))
        return actual
    }

    private fun assertSequenceMatchesOriginal(label: String, raw: String?, deltas: List<ModelUsageDelta>) {
        var expected = raw
        var actual = raw
        val working = MutableModelUsageLedger(raw.orEmpty())
        deltas.forEachIndexed { index, delta ->
            expected = originalApplyModelUsageDelta(expected, delta)
            actual = applyModelUsageDelta(actual, delta)
            working.apply(delta)
            assertJsonEquivalent("$label step $index", JSONObject(expected!!), JSONObject(actual!!))
            assertJsonEquivalent("$label cached step $index", JSONObject(expected!!), JSONObject(working.serialize()))
            assertEquals(decodeConversationUsageTotals(JSONObject(expected!!)), working.conversationTotalsSnapshot())
        }
        assertJsonEquivalent("$label one commit", JSONObject(expected!!),
            JSONObject(applyModelUsageDeltas(raw, deltas)))
    }

    /** Object key order is irrelevant; array order, every field and numeric value are not. */
    private fun assertJsonEquivalent(path: String, expected: Any?, actual: Any?) {
        when (expected) {
            is JSONObject -> {
                assertTrue("$path should be an object", actual is JSONObject)
                actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals("$path keys", keys, actual.keys().asSequence().toSet())
                keys.forEach { key -> assertJsonEquivalent("$path.$key", expected.get(key), actual.get(key)) }
            }
            is JSONArray -> {
                assertTrue("$path should be an array", actual is JSONArray)
                actual as JSONArray
                assertEquals("$path length", expected.length(), actual.length())
                for (index in 0 until expected.length()) {
                    assertJsonEquivalent("$path[$index]", expected.get(index), actual.get(index))
                }
            }
            is Number -> {
                assertTrue("$path should be a number", actual is Number)
                actual as Number
                assertEquals("$path numeric value", 0, expected.toString().toBigDecimal().compareTo(actual.toString().toBigDecimal()))
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    // Frozen pre-optimization oracle. Only local helper names were changed; the snapshot decoder
    // and collapsedByRound are unchanged production code. Do not delegate to the new seed/apply.
    private fun originalApplyModelUsageDelta(raw: String?, delta: ModelUsageDelta): String {
        if (delta.providerId.isBlank() || delta.modelId.isBlank()) return raw.orEmpty()
        if (delta.inputTokens <= 0L && delta.outputTokens <= 0L && delta.conversationId.isNullOrBlank()) {
            return raw.orEmpty()
        }
        val root = runCatching { JSONObject(originalSeedConversationUsage(raw, emptyMap())) }
            .getOrDefault(JSONObject())
        val providers = root.optJSONObject("providers") ?: JSONObject().also {
            root.put("providers", it)
        }
        val provider = providers.optJSONObject(delta.providerId) ?: JSONObject().also {
            providers.put(delta.providerId, it)
        }
        provider.put("name", delta.providerName.ifBlank { delta.providerId })
        val models = provider.optJSONObject("models") ?: JSONObject().also {
            provider.put("models", it)
        }
        val model = models.optJSONObject(delta.modelId) ?: JSONObject().also {
            models.put(delta.modelId, it)
        }
        model.put("displayName", delta.modelDisplayName.ifBlank { delta.modelId })
        val events = originalDecodeEvents(model.optJSONArray("events")).toMutableList()
        // Preserve cumulative counters when the detail window is trimmed; initialize old event-only data.
        for (field in listOf("inputTokens", "outputTokens", "cachedTokens", "cacheCreationTokens")) {
            if (!model.has(field)) model.put(field, events.sumOf {
                when (field) {
                    "inputTokens" -> it.inputTokens
                    "outputTokens" -> it.outputTokens
                    "cachedTokens" -> it.cachedTokens
                    else -> it.cacheCreationTokens
                }
            })
        }
        val incoming = ModelUsageEvent(
            atMillis = delta.atMillis,
            inputTokens = delta.inputTokens.coerceAtLeast(0L),
            outputTokens = delta.outputTokens.coerceAtLeast(0L),
            cachedTokens = delta.cachedTokens.coerceAtLeast(0L),
            cacheCreationTokens = delta.cacheCreationTokens.coerceAtLeast(0L),
            conversationId = delta.conversationId,
            round = delta.round,
            requestId = delta.requestId,
        )
        val replaceAt = events.indexOfLast { event ->
            if (!incoming.requestId.isNullOrBlank()) event.requestId == incoming.requestId
            else event.requestId == null && incoming.round != null &&
                !incoming.conversationId.isNullOrBlank() && event.round == incoming.round &&
                event.conversationId == incoming.conversationId
        }
        originalUpdateConversationUsage(root, incoming, events.getOrNull(replaceAt))
        if (replaceAt >= 0) {
            val previous = events[replaceAt]
            model.put("inputTokens", model.optLong("inputTokens") - previous.inputTokens + incoming.inputTokens)
            model.put("outputTokens", model.optLong("outputTokens") - previous.outputTokens + incoming.outputTokens)
            model.put("cachedTokens", model.optLong("cachedTokens") - previous.cachedTokens + incoming.cachedTokens)
            model.put("cacheCreationTokens", model.optLong("cacheCreationTokens") - previous.cacheCreationTokens + incoming.cacheCreationTokens)
            events[replaceAt] = incoming
        } else {
            model.put("inputTokens", model.optLong("inputTokens") + incoming.inputTokens)
            model.put("outputTokens", model.optLong("outputTokens") + incoming.outputTokens)
            model.put("cachedTokens", model.optLong("cachedTokens") + incoming.cachedTokens)
            model.put("cacheCreationTokens", model.optLong("cacheCreationTokens") + incoming.cacheCreationTokens)
            events += incoming
        }
        val conversations = originalStringSet(model.optJSONArray("conversations")).toMutableSet()
        delta.conversationId?.takeIf { it.isNotBlank() }?.let(conversations::add)
        model.put("conversations", JSONArray(conversations.sorted()))
        val days = originalStringSet(model.optJSONArray("days")).toMutableSet()
        days += delta.day.toString()
        model.put("days", JSONArray(days.sorted()))
        val trimmed = if (events.size > ORIGINAL_MAX_MODEL_EVENTS) {
            events.takeLast(ORIGINAL_MAX_MODEL_EVENTS)
        } else {
            events
        }
        model.put("events", originalEncodeEvents(trimmed))
        return root.toString()
    }

    private fun originalSeedConversationUsage(raw: String?, legacy: Map<String, ConversationUsageTotals>): String {
        val root = JSONObject(raw?.takeIf { it.isNotBlank() } ?: "{}")
        val totals = root.optJSONObject(ORIGINAL_CONVERSATION_TOTALS) ?: JSONObject().also { root.put(ORIGINAL_CONVERSATION_TOTALS, it) }
        if (legacy.isEmpty() && root.optBoolean("conversationTotalsInitialized")) return root.toString()
        val known = mutableMapOf<String, ConversationUsageTotals>()
        decodeModelUsageSnapshot(raw).providers.forEach { provider -> provider.models.forEach { model ->
            model.events.collapsedByRound().forEach { event ->
                event.conversationId?.takeIf { it.isNotBlank() }?.let { id ->
                    known[id] = (known[id] ?: ConversationUsageTotals()) +
                        ConversationUsageTotals(event.inputTokens, event.outputTokens, event.cachedTokens, event.cacheCreationTokens)
                }
            }
        } }
        (legacy.keys + known.keys).forEach { id ->
            if (!totals.has(id)) {
                val messages = legacy[id] ?: ConversationUsageTotals()
                val events = known[id] ?: ConversationUsageTotals()
                // Preserve the largest actually recorded cumulative value per field; missing history is not estimated.
                totals.put(id, JSONObject().put("in", maxOf(messages.input, events.input))
                    .put("out", maxOf(messages.output, events.output)).put("k", maxOf(messages.cached, events.cached))
                    .put("w", maxOf(messages.cacheCreation, events.cacheCreation)))
            }
        }
        root.put("conversationTotalsInitialized", true)
        return root.toString()
    }

    private fun originalUpdateConversationUsage(root: JSONObject, incoming: ModelUsageEvent, previous: ModelUsageEvent?) {
        val id = incoming.conversationId?.takeIf { it.isNotBlank() } ?: return
        val totals = root.optJSONObject(ORIGINAL_CONVERSATION_TOTALS) ?: JSONObject().also { root.put(ORIGINAL_CONVERSATION_TOTALS, it) }
        val item = totals.optJSONObject(id) ?: JSONObject().also { totals.put(id, it) }
        item.put("in", item.optLong("in") + incoming.inputTokens - (previous?.inputTokens ?: 0))
        item.put("out", item.optLong("out") + incoming.outputTokens - (previous?.outputTokens ?: 0))
        item.put("k", item.optLong("k") + incoming.cachedTokens - (previous?.cachedTokens ?: 0))
        item.put("w", item.optLong("w") + incoming.cacheCreationTokens - (previous?.cacheCreationTokens ?: 0))
    }

    private fun originalDecodeEvents(array: JSONArray?): List<ModelUsageEvent> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val at = item.optLong("t")
                if (at <= 0L) continue
                add(
                    ModelUsageEvent(
                        atMillis = at,
                        inputTokens = item.optLong("in"),
                        outputTokens = item.optLong("out"),
                        cachedTokens = item.optLong("k"),
                        cacheCreationTokens = item.optLong("w"),
                        conversationId = item.optString("c").takeIf { it.isNotBlank() },
                        round = if (item.has("r")) item.optInt("r") else null,
                        requestId = item.optString("q").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun originalEncodeEvents(events: List<ModelUsageEvent>): JSONArray =
        JSONArray().also { array ->
            events.forEach { event ->
                array.put(
                    JSONObject().also { json ->
                        json.put("t", event.atMillis)
                        json.put("in", event.inputTokens)
                        json.put("out", event.outputTokens)
                        if (event.cachedTokens > 0L) json.put("k", event.cachedTokens)
                        if (event.cacheCreationTokens > 0L) json.put("w", event.cacheCreationTokens)
                        json.put("c", event.conversationId.orEmpty())
                        event.round?.let { json.put("r", it) }
                        event.requestId?.let { json.put("q", it) }
                    },
                )
            }
        }

    private fun originalStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        const val ORIGINAL_MAX_MODEL_EVENTS = 4000
        const val ORIGINAL_CONVERSATION_TOTALS = "conversationTotalsV1"
    }
}
