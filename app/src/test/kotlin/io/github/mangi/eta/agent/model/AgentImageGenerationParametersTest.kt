package io.github.mangi.eta.agent.model

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImageGenerationParametersTest {
    private fun config(model: String = "grok-imagine-image-2.0") = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = model, systemPrompt = "",
        extraBodyJson = """{"eta_image_config":{"protocol":"passthrough","values":{"resolution":{"low":"1k","medium":"1.5k","high":"2k","ultra":"4k"}}}}""")
    private fun png(w: Int = 90, h: Int = 160): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
        }
    }
    private fun response(bytes: ByteArray) = JSONObject().put("data", JSONArray().put(JSONObject()
        .put("b64_json", Base64.getEncoder().encodeToString(bytes)))).toString()
    private fun client(requests: MutableList<Request>, result: String, code: Int = 200) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(result.toResponseBody()).build()
        }.build()
    private fun body(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test fun realGenerationBodyCarriesGrokRatioResolutionAndPerCallOverrides() {
        val requests = mutableListOf<Request>()
        val configured = config().copy(extraBodyJson = """{"size":"1024x1024","aspect_ratio":"1:1","resolution":"1k","eta_image_config":{"protocol":"passthrough","values":{"resolution":{"low":"1k","medium":"1.5k","high":"2k","ultra":"4k"}}}}""")
        val result = AgentImageGenerationClient(client(requests, response(png()))).generate(configured, "portrait",
            options = AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        val json = JSONObject(body(requests.single()))
        assertTrue(requests.single().url.encodedPath.endsWith("/images/generations"))
        assertEquals("9:16", json.getString("aspect_ratio")); assertEquals("2k", json.getString("resolution"))
        assertFalse(json.has("size")); assertEquals("portrait", json.getString("prompt"))
        assertTrue(configured.extraBodyJson.contains("1:1"))
        assertEquals(90, result.images.single().width); assertEquals(160, result.images.single().height)
        assertTrue(result.text.contains("IMAGE_RESOLUTION_UNVERIFIED")) // No declared pixel mapping: cannot claim this native tier is verified.
    }
    @Test fun squareResultIsReportedNotRetriedOrResized() {
        val requests = mutableListOf<Request>()
        val output = AgentImageGenerationClient(client(requests, response(png(100, 100)))).generate(config(), "portrait",
            options = AgentImageGenerationOptions(aspectRatio = "9:16"))
        assertEquals(1, requests.size)
        assertEquals(100, output.images.single().width)
        assertTrue(output.text.contains("IMAGE_DIMENSIONS_MISMATCH"))
    }
    @Test fun unsupportedParametersNeverCallApiAndEndpointFailureNeverFallsBackToChat() {
        val requests = mutableListOf<Request>()
        val generator = AgentImageGenerationClient(client(requests, """{"error":{"message":"unsupported endpoint"}}""", 404))
        assertThrows(ImageGenerationParameterException::class.java) {
            generator.generate(config(), "portrait", options = AgentImageGenerationOptions(aspectRatio = "9:16", size = "1024x1024"))
        }
        assertTrue(requests.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            generator.generate(config(), "portrait", options = AgentImageGenerationOptions(aspectRatio = "9:16"))
        }
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/images/generations"))
    }
    @Test fun grokEditIsJsonAndCarriesOptionsAndReferenceImage() {
        val requests = mutableListOf<Request>()
        AgentImageGenerationClient(client(requests, response(png()))).generate(config().copy(extraBodyJson = """{"eta_image_config":{"protocol":"passthrough","values":{"resolution":{"low":"1k","medium":"1.5k","high":"2k","ultra":"4k"}},"edit_protocol":"json_image_url"}}"""), "edit portrait",
            images = listOf(AgentImageGenerationClient.InputImage(png(), "image/png")),
            options = AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        assertTrue(requests.single().url.encodedPath.endsWith("/images/edits"))
        assertTrue(requests.single().body!!.contentType().toString().startsWith("application/json"))
        val json = JSONObject(body(requests.single()))
        assertEquals("9:16", json.getString("aspect_ratio")); assertEquals("2k", json.getString("resolution"))
        assertEquals("image_url", json.getJSONObject("image").getString("type"))
        assertTrue(json.getJSONObject("image").getString("url").startsWith("data:image/png;base64,"))
    }
    @Test fun openAiMultipartEditIncludesRequestedSizeAndDoesNotDropReferences() {
        val requests = mutableListOf<Request>()
        AgentImageGenerationClient(client(requests, response(png()))).generate(config("gpt-image-1"), "edit",
            images = listOf(AgentImageGenerationClient.InputImage(png(), "image/png")),
            options = AgentImageGenerationOptions(size = "1024x1536"))
        val encoded = body(requests.single())
        assertTrue(requests.single().body!!.contentType().toString().startsWith("multipart/form-data"))
        assertTrue(encoded.contains("name=\"size\"")); assertTrue(encoded.contains("1024x1536"))
        assertTrue(encoded.contains("name=\"image\""))
    }
    @Test fun gpt2RatioBecomesExactSizeAndConfiguredGrokDefaultsSurviveWithoutExplicitOptions() {
        val requests = mutableListOf<Request>()
        val gen = AgentImageGenerationClient(client(requests, response(png())))
        gen.generate(config("gpt-image-2").copy(extraBodyJson = """{"eta_image_config":{"protocol":"size","sizes":{"9:16":"864x1536"}}}"""), "portrait", options = AgentImageGenerationOptions(aspectRatio = "9:16"))
        val body1 = JSONObject(body(requests.single()))
        assertEquals("864x1536", body1.getString("size")); assertFalse(body1.has("aspect_ratio"))
        requests.clear()
        gen.generate(config().copy(extraBodyJson = """{"aspect_ratio":"9:16","resolution":"2k","eta_image_config":{"protocol":"passthrough","values":{"resolution":{"low":"1k","medium":"1.5k","high":"2k","ultra":"4k"}}}}"""), "portrait")
        assertEquals("9:16", JSONObject(body(requests.single())).getString("aspect_ratio"))
    }
    @Test fun ambiguousFailureOrSuccessfulEmptyResponseDoesNotGenerateAgain() {
        listOf(200, 400, 502, 503).forEach { code ->
            val requests = mutableListOf<Request>()
            assertThrows(IllegalStateException::class.java) {
                AgentImageGenerationClient(client(requests, "{}", code)).generate(config(), "image")
            }
            assertEquals(1, requests.size)
        }
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); throw java.io.IOException("response lost")
        }.build()
        assertThrows(java.io.IOException::class.java) {
            AgentImageGenerationClient(http).generate(config(), "image")
        }
        assertEquals(1, requests.size)
    }

    @Test fun userProvidedGrokCurlExampleIsPreservedInActualJsonRequest() {
        // An interceptor returns an error locally: no HTTP transport or paid image generation.
        val requests = mutableListOf<Request>()
        val expected = JSONObject("""{
            "model":"grok-imagine-image-2.0",
            "prompt":"A collage of London landmarks in a stenciled street-art style",
            "n":2,"aspect_ratio":"16:9","resolution":"2k","quality":"medium","response_format":"url"
        }""")
        val options = AgentImageGenerationOptions.fromJson(JSONObject(expected.toString()).also {
            it.remove("model"); it.remove("prompt")
        })
        val generator = AgentImageGenerationClient(client(requests, "{}", 400))
        assertThrows(IllegalStateException::class.java) {
            generator.generate(config().copy(baseUrl = "https://api.x.ai/v1"), expected.getString("prompt"), options = options)
        }
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.x.ai/v1/images/generations", request.url.toString())
        assertEquals("Bearer test", request.header("Authorization"))
        assertTrue(request.body!!.contentType().toString().startsWith("application/json"))
        val actual = JSONObject(body(request))
        assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
        expected.keys().forEach { key -> assertEquals("field $key", expected.get(key), actual.get(key)) }
        assertFalse(actual.has("size"))
    }

    @Test fun directInlineOptionsReachUnknownModelAndConfigNeverLeaks() {
        val requests = mutableListOf<Request>()
        val output = AgentImageGenerationClient(client(requests, response(png(100, 100)))).generate(
            config("agnes-image-2.5-flash"),
            "portrait\nimage_options: {\"aspect_ratio\":\"9:16\",\"resolution\":\"2k\"}",
        )
        val actual = JSONObject(body(requests.single()))
        assertEquals("9:16", actual.getString("aspect_ratio"))
        assertEquals("2k", actual.getString("resolution"))
        assertEquals("portrait", actual.getString("prompt"))
        assertFalse(actual.has("eta_image_config"))
        assertTrue(output.text.contains("分辨率:100x100"))
        assertTrue(output.text.contains("比例:9:16"))
        assertTrue(output.text.contains("IMAGE_DIMENSIONS_MISMATCH"))
        assertFalse(output.text.contains("发送参数"))
        assertFalse(output.text.contains("端点协议"))
        assertEquals(1, requests.size)
    }


    @Test fun defaultImagesPlanSendsExactSizeAndNoGeometryAliases() {
        val requests=mutableListOf<Request>()
        val result=AgentImageGenerationClient(client(requests,response(png(1152,2048)))).generate(
            config("any-model").copy(extraBodyJson=""), "cat", options=AgentImageGenerationOptions(aspectRatio="9:16",resolution="2k"))
        val json=JSONObject(body(requests.single()))
        assertEquals("1152x2048",json.getString("size"))
        assertEquals(setOf("model","prompt","n","size"),json.keys().asSequence().toSet())
        assertFalse(result.text.contains("MISMATCH"))
    }
    @Test fun jsonGenerationsEditCarriesReferenceAndMaskWithoutChangingEndpoint() {
        val requests=mutableListOf<Request>()
        AgentImageGenerationClient(client(requests,response(png()))).generate(
            config().copy(extraBodyJson="""{"eta_image_config":{"edit_protocol":"generations_image"}}"""), "edit cat",
            images=listOf(AgentImageGenerationClient.InputImage(png(),"image/png")),
            options=AgentImageGenerationOptions(size="90x160"), mask=AgentImageGenerationClient.InputImage(png(),"image/png"))
        val json=JSONObject(body(requests.single()))
        assertTrue(requests.single().url.encodedPath.endsWith("/images/generations"))
        assertTrue(json.getString("image").startsWith("data:image/png;base64,"))
        assertTrue(json.getString("mask").startsWith("data:image/png;base64,"))
        assertEquals("90x160",json.getString("size"))
        assertFalse(json.has("eta_image_config"))
    }
    @Test fun multipartMaskIsPreservedAndMismatchedMaskFailsBeforeRequest() {
        val requests=mutableListOf<Request>()
        val gen=AgentImageGenerationClient(client(requests,response(png())))
        val image=AgentImageGenerationClient.InputImage(png(),"image/png")
        gen.generate(config(),"edit",images=listOf(image),mask=image)
        assertTrue(body(requests.single()).contains("name=\"mask\""))
        requests.clear()
        assertThrows(IllegalArgumentException::class.java) {
            gen.generate(config(),"edit",images=listOf(image),mask=AgentImageGenerationClient.InputImage(png(100,100),"image/png"))
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun multipleJsonReferencesAndEmptyInputsAreNotDiscarded() {
        val requests=mutableListOf<Request>()
        val gen=AgentImageGenerationClient(client(requests,response(png())))
        val image=AgentImageGenerationClient.InputImage(png(),"image/png")
        assertThrows(ImageGenerationParameterException::class.java) {
            gen.generate(config().copy(extraBodyJson="""{"eta_image_config":{"edit_protocol":"generations_image"}}"""),"cat",images=listOf(image,image))
        }
        assertThrows(IllegalArgumentException::class.java) {
            gen.generate(config(),"cat",images=listOf(AgentImageGenerationClient.InputImage(byteArrayOf(),"image/png")))
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun missingImagesEndpointNeverFallsBackToChatEvenWithoutParameters() {
        val requests=mutableListOf<Request>()
        assertThrows(IllegalStateException::class.java) {
            AgentImageGenerationClient(client(requests,"{}",404)).generate(config().copy(extraBodyJson=""),"cat")
        }
        assertEquals(1,requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/images/generations"))
    }
    @Test fun anthropicRequiresExplicitImageEndpointContract() {
        val requests=mutableListOf<Request>()
        val gen=AgentImageGenerationClient(client(requests,response(png())))
        val anth=config().copy(providerType=io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC)
        assertThrows(ImageGenerationParameterException::class.java) { gen.generate(anth,"cat") }
        assertTrue(requests.isEmpty())
        gen.generate(anth.copy(extraBodyJson="""{"eta_image_config":{"endpoint":"openai_images"}}"""),"cat")
        assertEquals(1,requests.size)
        assertEquals("Bearer test",requests.single().header("Authorization"))
    }
    @Test fun nativeNovelAiBodyAndBinaryResponseUseSeparateProtocol() {
        val requests=mutableListOf<Request>()
        val image=png(832,1216)
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(image.toResponseBody()).build()
        }.build()
        val result=AgentImageGenerationClient(http).generate(config("nai-custom").copy(baseUrl="https://example.invalid",
            extraBodyJson="""{"eta_image_config":{"endpoint":"novelai_native","native_parameters":{"params_version":3,"steps":28,"scale":5,"sampler":"k_euler","seed":1}}}"""),
            "cat",options=AgentImageGenerationOptions(size="832x1216"))
        assertEquals("/ai/generate-image",requests.single().url.encodedPath)
        val json=JSONObject(body(requests.single()))
        assertEquals(setOf("model","input","action","parameters"),json.keys().asSequence().toSet())
        assertEquals("cat",json.getString("input")); assertEquals(832,json.getJSONObject("parameters").getInt("width"))
        assertEquals(1216,result.images.single().height)
        assertFalse(result.text.contains("MISMATCH"))
    }

    @Test fun malformedExtrasFailInsteadOfGeneratingWithDefaults() {
        val requests=mutableListOf<Request>()
        assertThrows(ImageGenerationParameterException::class.java) {
            AgentImageGenerationClient(client(requests,response(png()))).generate(config().copy(extraBodyJson="{broken"),"cat")
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun directNaturalPromptsReachTheActualRequestBuilder() {
        val requests=mutableListOf<Request>()
        val gen=AgentImageGenerationClient(client(requests,response(png())))
        val defaultConfig=config().copy(extraBodyJson="{}")
        gen.generate(defaultConfig,"生成一张2k、9:16的动漫美少女")
        assertEquals("1152x2048",JSONObject(body(requests.single())).getString("size"))
        requests.clear()
        gen.generate(defaultConfig,"生成一张动漫美少女1312×736")
        assertEquals("1312x736",JSONObject(body(requests.single())).getString("size"))
    }
    @Test fun concurrentNaturalRequestsUseMappedOneImageCountsAndNoSchedulerFields() {
        val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
        val config=config().copy(extraBodyJson="""{"eta_image_config":{"protocol":"size_long_edge","fields":{"n":"sample_count"}}}""")
        val gen=AgentImageGenerationClient(client(requests,response(png())))
        val result=gen.generate(config,"生成三张2k、9:16的插画，并发2")
        assertEquals(3,requests.size);assertEquals(3,result.images.size)
        requests.forEach {
            val json=JSONObject(body(it))
            assertEquals(1,json.getInt("sample_count"));assertFalse(json.has("n"));assertFalse(json.has("concurrency"))
            assertEquals("1152x2048",json.getString("size"))
        }
        assertTrue(result.text.contains("每次发送 n=1"));assertFalse(result.text.contains("\"sample_count\":3"))
    }
    @Test fun batchRetainsPartialResultsAndNeverRetriesFailures() {
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val code=if(calls.incrementAndGet()==2) 500 else 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body((if(code==200) response(png()) else "server failure").toResponseBody()).build()
        }.build()
        val result=AgentImageGenerationClient(http).generate(config(),"画三张插画，并发2")
        assertEquals(3,calls.get());assertEquals(2,result.images.size)
        assertTrue(result.text.contains("IMAGE_BATCH_PARTIAL"));assertTrue(result.text.contains("IMAGE_COUNT_MISMATCH"))
    }
    @Test fun invalidNaturalValuesNeverIssueRequests() {
        val requests=mutableListOf<Request>()
        val gen=AgentImageGenerationClient(client(requests,response(png())))
        for(prompt in listOf("画三张插画，并发9","生成1.5张插画","画三张2k、9:16插画，并发2.5","生成三张2k和4k的插画")) {
            assertThrows(ImageGenerationParameterException::class.java) { gen.generate(config(),prompt) }
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun nativeBatchGetsIndependentRandomSeedsAndOneSamplePerRequest() {
        val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
        val bytes=png(512,512)
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(bytes.toResponseBody()).build()
        }.build()
        val config=config().copy(extraBodyJson="""{"eta_image_config":{"endpoint":"novelai_native","native_parameters":{"params_version":3,"steps":28,"scale":5,"sampler":"k_euler"}}}""")
        val result=AgentImageGenerationClient(http).generate(config,"猫",options=AgentImageGenerationOptions(size="512x512",count=2,concurrency=2))
        assertEquals(2,requests.size);assertEquals(2,result.images.size)
        val seeds=requests.map {
            val json=JSONObject(body(it));assertFalse(json.has("concurrency"))
            val native=json.getJSONObject("parameters");assertEquals(1,native.getInt("n_samples"));native.getLong("seed")
        }
        assertEquals(2,seeds.distinct().size)
    }

    @Test fun mappedDefaultCountIsNotShadowedByClientDefault() {
        val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
        val cfg=config().copy(extraBodyJson="""{"params":{"samples":3},"concurrency":2,"eta_image_config":{"fields":{"n":"params.samples"}}}""")
        val result=AgentImageGenerationClient(client(requests,response(png()))).generate(cfg,"画一只猫")
        assertEquals(3,requests.size);assertEquals(3,result.images.size)
        requests.forEach { assertEquals(1,JSONObject(body(it)).getJSONObject("params").getInt("samples")) }
    }
    @Test fun oneResponseKeepsSuccessfulDownloadsWhenAnotherDownloadFails() {
        val posts=java.util.concurrent.atomic.AtomicInteger();val gets=java.util.concurrent.atomic.AtomicInteger()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val bytes=if(request.method=="POST") {
                posts.incrementAndGet()
                """{"data":[{"url":"https://example.invalid/one.png"},{"url":"https://example.invalid/two.png"}]}""".toByteArray()
            } else {
                gets.incrementAndGet();assertNull(request.header("Authorization"))
                if(request.url.encodedPath=="/two.png") throw java.io.IOException("test download failure")
                png()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(bytes.toResponseBody()).build()
        }.build()
        val result=AgentImageGenerationClient(http).generate(config(),"画两张插画")
        assertEquals(1,posts.get());assertEquals(2,gets.get());assertEquals(1,result.images.size)
        assertTrue(result.text.contains("IMAGE_DOWNLOAD_PARTIAL"));assertTrue(result.text.contains("IMAGE_COUNT_MISMATCH"))
    }

    @Test fun rejectionIncludesStatusAndShapeWithoutSendingAgain() {
        val requests=mutableListOf<Request>()
        val gen=AgentImageGenerationClient(client(requests,"""{"error":{"message":"The request could not be processed."}}""",400))
        val error=assertThrows(IllegalStateException::class.java) {
            gen.generate(config().copy(extraBodyJson="{}"),"private artwork description",options=AgentImageGenerationOptions(aspectRatio="9:16",resolution="2k"))
        }
        assertEquals(1,requests.size)
        assertTrue(error.message!!.contains("HTTP 400"));assertTrue(error.message!!.contains("1152x2048"))
        assertTrue(error.message!!.contains("未自动重试"));assertFalse(error.message!!.contains("private artwork description"))
        assertFalse(error.message!!.contains("test-key"))
    }

}
