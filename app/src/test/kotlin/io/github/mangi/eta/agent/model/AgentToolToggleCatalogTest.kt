package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolToggleCatalogTest {
    @Test fun disabledSchemaIsFilteredByCanonicalName() {
        val all = JSONArray().put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "tap")))
        assertTrue(AgentToolCatalog.filterEnabled(all, emptySet()).length() == 1)
        assertFalse(AgentToolCatalog.filterEnabled(all, setOf("tap_area")).length() == 1)
    }

    @Test fun gestureVariantsShareSwitchId() {
        assertTrue(Prefs.canonicalToolSwitchId("input_text") == "type_text")
        assertTrue(Prefs.canonicalToolSwitchId("browser_read") == "browser_use")
    }
}
