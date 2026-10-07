package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestBodyMergeTest {
    private fun body(key: String, value: String) = CustomBody(key, Json.parseToJsonElement(value))

    @Test fun preservesQuotedBooleanAndNumericStringsAtEveryDepth() {
        val target = JSONObject()
        RequestBodyMerge.mergeCustomBody(target, listOf(
            body("string_boolean", "\"true\""),
            body("string_number", "\"123\""),
            body("boolean", "true"),
            body("number", "123"),
            body("nothing", "null"),
            body("nested", "{\"boolean\":\"false\",\"number\":\"42\",\"array\":[\"true\",\"123\",false,456,null]}"),
        ))
        assertEquals("true", target.get("string_boolean"))
        assertEquals("123", target.get("string_number"))
        assertEquals(true, target.get("boolean"))
        assertEquals(123, target.get("number"))
        assertTrue(target.isNull("nothing"))
        val nested = target.getJSONObject("nested")
        assertEquals("false", nested.get("boolean"))
        assertEquals("42", nested.get("number"))
        val array = nested.getJSONArray("array")
        assertEquals("true", array.get(0))
        assertEquals("123", array.get(1))
        assertEquals(false, array.get(2))
        assertEquals(456, array.get(3))
        assertTrue(array.isNull(4))
    }

    @Test fun recursiveObjectMergeAndArrayReplacementRemainCallerOwned() {
        val target = JSONObject("""{"options":{"keep":true,"change":0},"items":["old"]}""")
        RequestBodyMerge.mergeCustomBody(target, listOf(
            body("options", "{\"change\":\"123\"}"),
            body("items", "[\"new\"]"),
        ))
        assertTrue(target.getJSONObject("options").getBoolean("keep"))
        assertEquals("123", target.getJSONObject("options").get("change"))
        assertEquals(1, target.getJSONArray("items").length())
        assertEquals("new", target.getJSONArray("items").getString(0))
    }
}
