package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.screens.tools.AgentToolsCatalogUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ToolSwitchCoverageTest {
    @Test fun schemasAreUniqueAndFileWebToolsHaveSwitches() {
        val schemas = AgentToolCatalog.allForSwitches()
        val names = (0 until schemas.length()).map {
            schemas.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(AgentFileToolCatalog.names))
        assertTrue(names.containsAll(listOf("web_search", "fetch_url")))
        val groups = AgentToolsCatalogUi.complete(emptyList())
        assertTrue(groups.first { it.id == "terminal" }.tools.map { it.id }.containsAll(AgentFileToolCatalog.names))
        assertTrue(groups.first { it.id == "web" }.tools.map { it.id }.containsAll(listOf("web_search", "fetch_url")))
        val switches = groups.flatMap { it.tools }.map { Prefs.canonicalToolSwitchId(it.id) }.toSet()
        assertEquals(names.map(Prefs::canonicalToolSwitchId).toSet(), switches)
    }
}
