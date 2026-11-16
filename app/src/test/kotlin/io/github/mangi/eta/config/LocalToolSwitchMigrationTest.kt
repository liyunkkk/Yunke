package io.github.mangi.eta.config

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalToolSwitchMigrationTest {
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "local-tools-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test fun oldBrowserAndTerminalOffBecomeIndividualDisabledTools() {
        val prefs = prefs()
        prefs.edit().putBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false)
            .putBoolean(Prefs.Keys.AGENT_TERMINAL_TOOLS, false).commit()
        Prefs.migrateLegacyGroupSwitchesIfNeeded(prefs)
        val disabled = Prefs.disabledTools(prefs)
        assertTrue(prefs.getBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false))
        assertTrue(prefs.getBoolean(Prefs.Keys.AGENT_TERMINAL_TOOLS, false))
        assertTrue(disabled.containsAll(AgentFileToolCatalog.names))
        assertTrue(disabled.containsAll(listOf("web_search", "fetch_url", "browser_use", "read_image")))
        assertFalse(Prefs.isToolEnabled("browser_read", prefs))
        assertTrue(Prefs.isToolEnabled("search_apps", prefs))
    }

    @Test fun migrationDoesNotOverwriteExistingPerToolSelection() {
        val prefs = prefs()
        prefs.edit().putString(Prefs.Keys.AGENT_DISABLED_TOOLS_JSON, "[\"web_search\"]")
            .putBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false).commit()
        Prefs.migrateLegacyGroupSwitchesIfNeeded(prefs)
        assertEquals(setOf("web_search"), Prefs.disabledTools(prefs))
    }

    @Test fun malformedJsonAndVariantsRemainDeterministic() {
        val prefs = prefs()
        prefs.edit().putString(Prefs.Keys.AGENT_DISABLED_TOOLS_JSON, "[").commit()
        assertTrue(Prefs.disabledTools(prefs).isEmpty())
        prefs.edit().putString(Prefs.Keys.AGENT_DISABLED_TOOLS_JSON, "[\"input_text\"]").commit()
        assertFalse(Prefs.isToolEnabled("paste_text", prefs))
    }
}
