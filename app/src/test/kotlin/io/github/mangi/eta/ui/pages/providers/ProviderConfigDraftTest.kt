package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.saveable.SaverScope
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProviderConfigDraftTest {
    private fun body(key: String, value: String) = CustomBody(key, Json.parseToJsonElement(value))
    private fun provider(bodies: List<CustomBody> = emptyList()) = AnthropicProviderSetting(
        id = "provider-test", name = "Claude", baseUrl = "https://example.invalid", customBody = bodies,
    )

    @Test fun emptyBodyDraftIsValid() {
        assertEquals(emptyList<CustomBody>(), parseProviderBodies(emptyList()).getOrThrow())
    }

    @Test fun parsesAllJsonTypesAndTrimsFieldNames() {
        val values = listOf("\"1h\"", "true", "123", "null", "{\"nested\":[1,false]}", "[1,\"two\"]", "\"true\"", "\"123\"")
        val parsed = parseProviderBodies(values.mapIndexed { index, value ->
            ProviderBodyDraft(key = " field$index ", valueJson = value)
        }).getOrThrow()
        assertEquals(values.mapIndexed { index, value -> body("field$index", value) }, parsed)
        assertTrue((parsed[0].value as JsonPrimitive).isString)
        assertFalse((parsed[1].value as JsonPrimitive).isString)
        assertFalse((parsed[2].value as JsonPrimitive).isString)
        assertEquals(JsonNull, parsed[3].value)
        assertTrue((parsed[6].value as JsonPrimitive).isString)
        assertTrue((parsed[7].value as JsonPrimitive).isString)
    }

    @Test fun rejectsBlankAndDuplicateFieldNames() {
        assertTrue(parseProviderBodies(listOf(ProviderBodyDraft(key = " ", valueJson = "1"))).isFailure)
        assertTrue(parseProviderBodies(listOf(
            ProviderBodyDraft(key = " key ", valueJson = "1"),
            ProviderBodyDraft(key = "key", valueJson = "2"),
        )).isFailure)
    }

    @Test fun rejectsIncompleteAndNonJsonLiteralsIncludingNestedValues() {
        for (value in listOf("", "1h", "undefined", "NaN", "Infinity", "01", "+1", "[NaN]", "{\"x\":undefined}", "{", "true false")) {
            assertTrue("Must reject invalid JSON", parseProviderBodies(listOf(
                ProviderBodyDraft(key = "field", valueJson = value),
            )).isFailure)
        }
    }

    @Test fun parseErrorsDoNotExposeEnteredSecrets() {
        val error = parseProviderBodies(listOf(
            ProviderBodyDraft(key = "private_field", valueJson = "\"SECRET_SENTINEL"),
        )).exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("SECRET_SENTINEL"))
        assertNull(error.cause)
    }

    @Test fun fromProviderKeepsJsonRepresentationsAndOrdering() {
        val bodies = listOf(body("eta_prompt_cache", "\"5m\""), body("options", "{\"enabled\":true}"))
        val draft = ProviderConfigDraft.from(provider(bodies))
        assertEquals(bodies.map { it.key }, draft.bodies.map { it.key })
        assertEquals(bodies, parseProviderBodies(draft.bodies).getOrThrow())
    }

    @Test fun saverPreservesIncompleteBodyDraftAndStableRowIds() {
        val draft = ProviderConfigDraft.from(provider()).copy(bodies = listOf(
            ProviderBodyDraft(id = "row-1", key = "eta_prompt_cache", valueJson = "\"1h\""),
            ProviderBodyDraft(id = "row-2", key = "unfinished", valueJson = "{"),
        ))
        val saved = with(ProviderConfigDraftSaver) { SaverScope { true }.save(draft) }
        assertEquals(draft, ProviderConfigDraftSaver.restore(requireNotNull(saved)))
    }

    @Test fun updatedBodiesArePersistedForAllProviderTypesAndCanBeDeleted() {
        val providers: List<ProviderSetting> = listOf(
            provider(),
            OpenAiCompatibleProviderSetting(id = "openai", name = "OpenAI", baseUrl = "https://example.invalid"),
            CustomProviderSetting(id = "custom", name = "Custom", baseUrl = "https://example.invalid"),
        )
        val bodies = parseProviderBodies(listOf(
            ProviderBodyDraft(key = "eta_prompt_cache", valueJson = "\"1h\""),
        )).getOrThrow()
        for (source in providers) {
            val updated = update(source, bodies)
            assertEquals(source::class, updated::class)
            assertEquals(bodies, updated.customBody)
            assertEquals(bodies, parseProviderBodies(ProviderConfigDraft.from(updated).bodies).getOrThrow())
            assertTrue(update(updated, emptyList()).customBody.isEmpty())
        }
    }

    @Test fun omittedCustomBodyArgumentPreservesExistingProviderFields() {
        val source = provider(listOf(body("existing", "true")))
        val draft = ProviderConfigDraft.from(source)
        val updated = buildUpdatedProvider(
            source = source, name = draft.name, baseUrl = draft.baseUrl, apiKey = draft.apiKey,
            isEnabled = draft.isEnabled, endpointMode = draft.endpointMode,
            hostedWebSearchEnabled = draft.hostedWebSearchEnabled, anthropicVersion = draft.anthropicVersion,
            customHeaders = source.customHeaders, balanceOption = draft.balanceOption,
        )
        assertEquals(source.customBody, updated.customBody)
    }

    private fun update(source: ProviderSetting, bodies: List<CustomBody>): ProviderSetting {
        val draft = ProviderConfigDraft.from(source)
        return buildUpdatedProvider(
            source = source, name = draft.name, baseUrl = draft.baseUrl, apiKey = draft.apiKey,
            isEnabled = draft.isEnabled, endpointMode = draft.endpointMode,
            hostedWebSearchEnabled = draft.hostedWebSearchEnabled, anthropicVersion = draft.anthropicVersion,
            customHeaders = source.customHeaders, customBody = bodies, balanceOption = draft.balanceOption,
        )
    }
}
