package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileToolCatalogTest {
    @Test fun catalogPublishesSevenStructuredFileToolsOnce() {
        val tools = JSONArray()
        AgentTerminalToolCatalog.appendTo(tools)
        val functions = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
        val names = functions.map { it.getString("name") }
        AgentFileToolCatalog.names.forEach { assertEquals(1, names.count { name -> name == it }) }
        functions.filter { it.getString("name") in AgentFileToolCatalog.names }.forEach {
            val parameters = it.getJSONObject("parameters")
            assertFalse(parameters.getBoolean("additionalProperties"))
            val properties = parameters.getJSONObject("properties")
            assertTrue(properties.has("environment"))
            assertTrue(properties.has("identity"))
            assertTrue(properties.has("cwd"))
        }
    }

    @Test fun exactEditAcceptsEmptyReplacementAndReadPublishesContinuationArguments() {
        val tools = JSONArray()
        AgentFileToolCatalog.appendTo(tools)
        val functions = (0 until tools.length()).associate {
            val function = tools.getJSONObject(it).getJSONObject("function")
            function.getString("name") to function
        }
        val edit = functions.getValue("edit_file").getJSONObject("parameters").getJSONObject("properties")
        assertEquals(1, edit.getJSONObject("old_text").getInt("minLength"))
        assertFalse(edit.getJSONObject("new_text").has("minLength"))
        val read = functions.getValue("read_file").getJSONObject("parameters").getJSONObject("properties")
        listOf("offset_bytes", "max_bytes", "start_line", "max_lines", "expected_revision").forEach {
            assertTrue(read.has(it))
        }
    }
}
