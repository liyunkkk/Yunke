package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.vivo.VivoBridgeDiagnostics.Reason
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class VivoCandidateReaderTest {
    // Small getter/field fixtures, not vendor classes. Any? deliberately exercises bad runtime
    // types and a broad getModel declaration; no duplicated eligibility implementation.
    private class Payload
    private class Request(var model: Any?, var dialogId: Any? = "dialog", var conversationId: Any? = "conversation")
    private open class Model {
        var agentId: Any? = "little_v"
        var inputType: Any? = 0
        var bizSource: Any? = null
        var renderText: Any? = true
        var renderAttachment: Any? = true
        var deepThink: Any? = false
        var shortcut: Any? = false
        var regenerate: Any? = false
        var skipRemote: Any? = false
        var fromRecommend: Any? = false
        var attachmentQueryModel: Any? = null
        var cameraContext: Any? = null
        var psAgentContext: Any? = null
        var twsNotificationContext: Any? = null
        var extraParams: Any? = null
        var scheduleContext: Any? = null
        var botType: Any? = null
        var intentions: Any? = Intentions()
        var newQueryParams: Any? = NewQueryParams()
        open var displayQuery: Any? = "/agent hello"
        var serverQuery: Any? = "/agent server"
    }
    private class Intentions(val first: Any? = null, val second: Any? = null, val third: Any? = null)
    private class NewQueryParams(val params: Any? = null, val isNewQuery: Boolean = false)
    private val sources = listOf(null, "", "BottomInput")
    private val botTypes = listOf(null, "", " \t\n", "main")
    private val allowedShapes = sources.flatMap { source -> botTypes.map { source to it } }
    private val reader = VivoCandidateReader(Request::class.java, Model::class.java, Payload::class.java,
        Intentions::class.java, NewQueryParams::class.java)
    private fun read(model: Model, requirePrefix: Boolean = true) = reader.read(Payload(), Request(model), requirePrefix)
    private fun rejected(reason: Reason, result: VivoCandidateReader.Result) {
        assertEquals(VivoCandidateReader.Rejected(reason), result)
    }

    @Test fun observedNativeManualTextShapeAcceptsExactMainUsingServerQueryFallback() {
        val model = Model().apply {
            agentId = "little_v"
            inputType = 0
            bizSource = "BottomInput"
            botType = "main"
            renderText = true
            renderAttachment = true
            deepThink = false
            regenerate = false
            shortcut = false
            skipRemote = false
            fromRecommend = false
            attachmentQueryModel = null
            psAgentContext = null
            cameraContext = null
            extraParams = null
            twsNotificationContext = null
            scheduleContext = null
            intentions = Intentions(first = null, second = null, third = null)
            newQueryParams = NewQueryParams(params = null, isNewQuery = false)
            displayQuery = null
            serverQuery = "/agent 请只回复：V2419A-接管验收-03"
        }
        assertEquals(VivoCandidateReader.Accepted("dialog", "conversation", "请只回复：V2419A-接管验收-03"),
            read(model, requirePrefix = true))
    }

    @Test fun allowedSourcesAndBotTypesAcceptRealReaderAndRuntimeModelSubclasses() {
        // getModel's declared return type is Object, not the model class; runtime isInstance wins.
        assertEquals(Any::class.java, Request::class.java.getDeclaredMethod("getModel").returnType)
        allowedShapes.forEach { (source, type) ->
            val model = object : Model() {}.apply { bizSource = source; botType = type }
            assertEquals(VivoCandidateReader.Accepted("dialog", "conversation", "hello"), read(model))
            model.displayQuery = " "
            assertEquals(VivoCandidateReader.Accepted("dialog", "conversation", "server"), read(model))
            model.displayQuery = null
            assertEquals(VivoCandidateReader.Accepted("dialog", "conversation", "server"), read(model))
        }
    }

    @Test fun objectNullAndWrongClassGatesAreExplicitForEveryAllowedShape() {
        allowedShapes.forEach { (source, type) ->
            val request = Request(Model().apply { bizSource = source; botType = type })
            rejected(Reason.MAPPED_NULL, reader.read(null, request, true))
            rejected(Reason.MAPPED_TYPE, reader.read(Any(), request, true))
            rejected(Reason.REQUEST_NULL, reader.read(Payload(), null, true))
            rejected(Reason.REQUEST_TYPE, reader.read(Payload(), Any(), true))
            request.model = null
            rejected(Reason.MODEL_NULL, reader.read(Payload(), request, true))
            request.model = Any()
            rejected(Reason.MODEL_TYPE, reader.read(Payload(), request, true))
        }
    }

    @Test fun everyAllowedSourceAndBotTypeCrossesAllScalarAndSpecializedRejections() {
        val cases: List<Pair<Reason, Model.() -> Unit>> = listOf(
            Reason.AGENT_ID to { agentId = "other" },
            Reason.AGENT_ID to { agentId = null },
            Reason.INPUT_TYPE to { inputType = 1 },
            Reason.RENDER_TEXT to { renderText = false },
            Reason.SHORTCUT to { shortcut = true },
            Reason.REGENERATE to { regenerate = true },
            Reason.SKIP_REMOTE to { skipRemote = true },
            Reason.RECOMMENDED to { fromRecommend = true },
            Reason.ATTACHMENT to { attachmentQueryModel = Any() },
            Reason.CAMERA_CONTEXT to { cameraContext = Any() },
            Reason.PS_AGENT_CONTEXT to { psAgentContext = Any() },
            Reason.TWS_NOTIFICATION_CONTEXT to { twsNotificationContext = Any() },
            Reason.EXTRA_PARAMS to { extraParams = emptyMap<String, String>() },
            Reason.SCHEDULE_CONTEXT to { scheduleContext = "schedule" },
            Reason.BOT_TYPE to { botType = "bot" },
            Reason.INTENTIONS to { intentions = Intentions(first = "intent") },
            Reason.INTENTIONS to { intentions = Intentions(second = "intent") },
            Reason.INTENTIONS to { intentions = Intentions(third = "intent") },
            Reason.NEW_QUERY_PARAMS to { newQueryParams = NewQueryParams(Any()) },
            Reason.NEW_QUERY_PARAMS to { newQueryParams = NewQueryParams(emptyMap<String, String>()) },
        )
        allowedShapes.forEach { (source, type) ->
            cases.forEach { (reason, mutate) ->
                rejected(reason, read(Model().apply { bizSource = source; botType = type }.apply(mutate)))
            }
        }
    }

    @Test fun nullableStringsNeverCoerceWrongTypesToAbsentIncludingIntentFields() {
        val bad = object {
            override fun toString(): String = error("must not inspect dynamic values")
        }
        val cases: List<Pair<Reason, Model.() -> Unit>> = listOf(
            Reason.AGENT_ID_TYPE to { agentId = bad },
            Reason.BIZ_SOURCE_TYPE to { bizSource = bad },
            Reason.SCHEDULE_CONTEXT_TYPE to { scheduleContext = bad },
            Reason.BOT_TYPE_TYPE to { botType = bad },
            Reason.INTENTION_TEXT_TYPE to { intentions = Intentions(first = bad) },
            Reason.INTENTION_TEXT_TYPE to { intentions = Intentions(second = bad) },
            Reason.INTENTION_TEXT_TYPE to { intentions = Intentions(third = bad) },
            Reason.DISPLAY_QUERY_TYPE to { displayQuery = bad },
            Reason.SERVER_QUERY_TYPE to { serverQuery = bad },
            Reason.INTENTIONS_TYPE to { intentions = bad },
            Reason.NEW_QUERY_PARAMS_TYPE to { newQueryParams = bad },
        )
        allowedShapes.forEach { (source, type) ->
            cases.forEach { (reason, mutate) ->
                rejected(reason, read(Model().apply { bizSource = source; botType = type }.apply(mutate)))
            }
        }
    }

    @Test fun primitiveFieldsRequireTheirActualNonNullRuntimeType() {
        val cases: List<Triple<String, Reason, Any>> = listOf(
            Triple("inputType", Reason.INPUT_TYPE_TYPE, 0L),
            Triple("renderText", Reason.RENDER_TEXT_TYPE, "true"),
            Triple("shortcut", Reason.SHORTCUT_TYPE, 0),
            Triple("regenerate", Reason.REGENERATE_TYPE, "false"),
            Triple("skipRemote", Reason.SKIP_REMOTE_TYPE, 0),
            Triple("fromRecommend", Reason.RECOMMENDED_TYPE, "false"),
        )
        allowedShapes.forEach { (source, type) ->
            cases.forEach { (name, reason, wrongType) ->
                listOf(null, wrongType).forEach { value ->
                    val model = Model().apply { bizSource = source; botType = type }
                    Model::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
                    rejected(reason, read(model))
                }
            }
        }
    }

    @Test fun defaultSpecializedContainersAndBlankNullableStringsRetainOldMeaning() {
        allowedShapes.forEach { (source, type) ->
            listOf(null, "", " \t\n").forEach { blank ->
                val model = Model().apply {
                    bizSource = source
                    scheduleContext = blank
                    botType = type
                    intentions = Intentions(blank, blank, blank)
                    newQueryParams = NewQueryParams(null)
                }
                assertTrue(read(model) is VivoCandidateReader.Accepted)
                model.intentions = null
                model.newQueryParams = null
                assertTrue(read(model) is VivoCandidateReader.Accepted)
            }
        }
    }

    @Test fun sourceVariantsAreRejectedByTheProductionReaderForEveryAllowedBotType() {
        botTypes.forEach { type ->
            listOf("voice_click", "BottomInput.VoiceClick", "BottomInput.LongPress", "BottomInput.VoiceLongPress",
                "bottominput", "BOTTOMINPUT", "bottomInput", " BottomInput", "BottomInput ", "BottomInput\n",
                "BottomInput\u0000", "BottomInput.text", "BottomInputSuffix", " ", "\t").forEach { source ->
                rejected(Reason.BIZ_SOURCE, read(Model().apply { bizSource = source; botType = type }))
            }
            listOf(0, false, StringBuilder(""), emptyList<String>()).forEach { badSource ->
                rejected(Reason.BIZ_SOURCE_TYPE, read(Model().apply { bizSource = badSource; botType = type }))
            }
        }
    }

    @Test fun onlyExactMainOrLegacyNullAndBlankBotTypesAreAccepted() {
        val nonStringMain = object {
            override fun toString(): String = "main"
        }
        sources.forEach { source ->
            listOf("bot", "special", "Main", "MAIN", " main", "main ", "main\n", "main\u0000",
                "\tmain", "main\t", "main\r\n", "main.suffix", "mainSuffix", "main/other").forEach { type ->
                rejected(Reason.BOT_TYPE, read(Model().apply { bizSource = source; botType = type }))
            }
            listOf(0, false, StringBuilder("main"), emptyList<String>(), nonStringMain).forEach { type ->
                rejected(Reason.BOT_TYPE_TYPE, read(Model().apply { bizSource = source; botType = type }))
            }
        }
        allowedShapes.forEach { (source, type) ->
            assertEquals(VivoCandidateReader.Accepted("dialog", "conversation", "hello"),
                read(Model().apply { bizSource = source; botType = type }))
        }
    }

    @Test fun idsAndPromptGatesRemainRequiredForEveryAllowedSourceAndBotType() {
        allowedShapes.forEach { (source, type) ->
            val model = Model().apply { bizSource = source; botType = type }
            listOf(null, "", "bad id", "id\u0000", "x".repeat(161)).forEach { id ->
                rejected(Reason.DIALOG_ID, reader.read(Payload(), Request(model, dialogId = id), true))
                rejected(Reason.CONVERSATION_ID, reader.read(Payload(), Request(model, conversationId = id), true))
            }
            rejected(Reason.DIALOG_ID_TYPE, reader.read(Payload(), Request(model, dialogId = 42), true))
            rejected(Reason.CONVERSATION_ID_TYPE, reader.read(Payload(), Request(model, conversationId = 42), true))
            listOf(null, "", " ", "/agent", "/agent ", "/agenthello", "hello", "/agent a\u0000b",
                "/agent " + "x".repeat(4001)).forEach { text ->
                model.displayQuery = text
                model.serverQuery = text
                rejected(Reason.PROMPT, read(model))
            }
            model.displayQuery = "hello"
            assertEquals(VivoCandidateReader.Accepted("dialog", "conversation", "hello"), read(model, false))
            listOf(null, "", " ", "a\u0000b", "x".repeat(4001)).forEach { text ->
                model.displayQuery = text
                model.serverQuery = text
                rejected(Reason.PROMPT, read(model, false))
            }
        }
    }

    @Test fun firstRejectionIsDeterministicAndDoesNotDescribeSensitiveValues() {
        val secret = "prompt=private id=private endpoint=https://private.invalid token=private"
        val model = Model().apply {
            agentId = secret
            inputType = 1
            attachmentQueryModel = Any()
            scheduleContext = secret
            displayQuery = secret
        }
        repeat(5) {
            rejected(Reason.MAPPED_NULL, reader.read(null, null, true))
            rejected(Reason.AGENT_ID, read(model))
        }
        model.agentId = "little_v"
        rejected(Reason.INPUT_TYPE, read(model))
        model.inputType = 0
        rejected(Reason.ATTACHMENT, read(model))
        model.attachmentQueryModel = null
        rejected(Reason.SCHEDULE_CONTEXT, read(model))
    }

    @Test fun getterFailuresAreNotConvertedToAbsentOrAnAcceptedCandidate() {
        val failure = IllegalStateException("private exception message")
        val model = object : Model() {
            override var displayQuery: Any?
                get() = throw failure
                set(value) = Unit
        }
        try {
            read(model)
            fail("reflection failure must propagate to the hook's existing fixed-category handler")
        } catch (error: InvocationTargetException) {
            assertSame(failure, error.cause)
        }
    }
}
