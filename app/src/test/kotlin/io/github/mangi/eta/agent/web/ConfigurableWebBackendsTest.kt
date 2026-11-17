package io.github.mangi.eta.agent.web

import io.github.mangi.eta.data.model.CustomWebSearchConfig
import io.github.mangi.eta.data.model.WebHttpMethod
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurableWebBackendsTest {
    @Test fun endpointNormalizesCommonDeepSeekBaseUrls() {
        listOf("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/v1", "https://api.deepseek.com/v1/tools").forEach {
            assertEquals("https://api.deepseek.com/v1/tools/search", ConfigurableWebBackends.deepSeekEndpoint(it, "search"))
        }
        assertEquals("https://proxy.test/tools/fetch", ConfigurableWebBackends.deepSeekEndpoint("https://proxy.test/tools/fetch", "fetch"))
    }

    @Test fun jsonPathSupportsRootNestedFieldsArrayOffsetsAndNulls() {
        val root = JSONObject().put("data", JSONArray().put(JSONObject().put("text", "正文")).put(JSONObject.NULL))
        assertEquals(root, ConfigurableWebBackends.resolveJsonPath(root, "$"))
        assertEquals("正文", ConfigurableWebBackends.resolveJsonPath(root, "$.data.0.text"))
        assertNull(ConfigurableWebBackends.resolveJsonPath(root, "data.1"))
        assertNull(ConfigurableWebBackends.resolveJsonPath(root, "data.99.text"))
    }

    @Test fun explicitAuthorizationOverridesAutomaticBearer() {
        val headers = ConfigurableWebBackends.parseCustomHeaders("authorization: Basic fixed\nAccept: application/json", "secret")
        assertEquals("Basic fixed", headers["authorization"])
        assertEquals(2, headers.size)
        assertEquals("Bearer secret", ConfigurableWebBackends.parseCustomHeaders("", "secret")["Authorization"])
    }

    @Test fun postTemplateQuotesUnicodeControlCharactersAndQuery() {
        val query = "中文 \"quoted\"\n\\slash"
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            assertEquals(query, payload.getString("query"))
            assertEquals(3, payload.getInt("count"))
            assertEquals("Bearer credential", request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json").body("{\"results\":[]}".toResponseBody()).build()
        }).build()
        WebHttpTransport(client).use { transport ->
            val result = ConfigurableWebBackends.searchCustom(transport, CustomWebSearchConfig(url = "https://custom.test/search"), "credential", query, 3) {}
            assertTrue(result.results.isEmpty())
        }
    }

    @Test fun getTemplateEncodesQueryAndCountWithoutRequestBody() {
        val query = "中文 & quoted"
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals(query, request.url.queryParameter("q"))
            assertEquals("2", request.url.queryParameter("count"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json").body("{\"results\":[]}".toResponseBody()).build()
        }).build()
        WebHttpTransport(client).use { transport ->
            ConfigurableWebBackends.searchCustom(transport, CustomWebSearchConfig(method = WebHttpMethod.GET, url = "https://custom.test/search?q={{query}}&count={{count}}"), "", query, 2) {}
        }
    }
}
