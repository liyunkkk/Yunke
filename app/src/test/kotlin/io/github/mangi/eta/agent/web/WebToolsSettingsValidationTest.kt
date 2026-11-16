package io.github.mangi.eta.agent.web

import io.github.mangi.eta.data.model.CustomWebFetchConfig
import io.github.mangi.eta.data.model.CustomWebSearchConfig
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.ui.screens.tools.WebToolsValidationFailure
import io.github.mangi.eta.ui.screens.tools.validateWebToolsSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebToolsSettingsValidationTest {
    @Test fun defaultsUsePublicServicesWithoutCredentials() {
        val settings = WebToolsSettings()
        assertEquals(WebToolBackend.BUILTIN, settings.searchBackend)
        assertEquals(WebToolBackend.BUILTIN, settings.fetchBackend)
        validateWebToolsSettings(settings, WebToolsCredentials(), false)
    }

    @Test fun deepSeekCanReuseProviderKeyButNeedsACredentialSource() {
        val settings = WebToolsSettings(searchBackend = WebToolBackend.DEEPSEEK)
        validateWebToolsSettings(settings, WebToolsCredentials(), true)
        validateWebToolsSettings(settings, WebToolsCredentials(deepSeekApiKey = "key"), false)
        assertThrows(WebToolsValidationFailure::class.java) {
            validateWebToolsSettings(settings, WebToolsCredentials(), false)
        }
    }

    @Test fun customConfigurationValidatesTemplatesAndHeaderSyntax() {
        val settings = WebToolsSettings(
            searchBackend = WebToolBackend.CUSTOM,
            fetchBackend = WebToolBackend.CUSTOM,
            customSearch = CustomWebSearchConfig(url = "https://search.test?q={{query}}&count={{count}}"),
            customFetch = CustomWebFetchConfig(url = "https://fetch.test?url={{url}}"),
        )
        validateWebToolsSettings(settings, WebToolsCredentials(), false)
        assertThrows(WebToolsValidationFailure::class.java) {
            validateWebToolsSettings(settings.copy(customSearch = settings.customSearch.copy(headersText = "missing-colon")), WebToolsCredentials(), false)
        }
        assertThrows(WebToolsValidationFailure::class.java) {
            validateWebToolsSettings(settings.copy(customFetch = settings.customFetch.copy(url = "file:///private")), WebToolsCredentials(), false)
        }
    }

    @Test fun configurationRoundTripsSeparatelyFromCredentials() {
        val json = Json { encodeDefaults = true }
        val settings = WebToolsSettings(customSearch = CustomWebSearchConfig(resultsPath = "data.items", titleField = "fields.title"))
        assertEquals(settings, json.decodeFromString<WebToolsSettings>(json.encodeToString(settings)))
        val credentials = WebToolsCredentials(deepSeekApiKey = "ds", customSearchApiKey = "search", customFetchApiKey = "fetch")
        assertEquals(credentials, json.decodeFromString<WebToolsCredentials>(json.encodeToString(credentials)))
    }
}
