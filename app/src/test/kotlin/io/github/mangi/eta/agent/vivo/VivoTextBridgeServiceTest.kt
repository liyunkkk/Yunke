package io.github.mangi.eta.agent.vivo

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VivoTextBridgeServiceTest {
    private lateinit var diagnosticScope: VivoDiagnosticTestScope
    private val secret = "private-prompt Authorization: Bearer private-key https://private.invalid model=private"
    private val modelConfig = AgentModelClient.ModelConfig(providerId = "private-provider",
        baseUrl = "https://private.invalid", apiKey = "private-key", model = "private-model")

    @Before fun isolateDiagnostics() {
        diagnosticScope = VivoDiagnosticTestScope()
        ShadowLog.clear()
    }

    @After fun restoreDiagnostics() {
        if (::diagnosticScope.isInitialized) diagnosticScope.restore()
        ShadowLog.clear()
    }

    private fun logs() = ShadowLog.getLogsForTag("EtaVivoText").map { it.msg }

    private fun execute(
        controller: AgentRunController = AgentRunController(),
        select: () -> AgentModelClient.ModelConfig? = { modelConfig },
        complete: (AgentModelClient.ModelConfig, JSONArray) -> String,
    ): Pair<String, String?> {
        val delivered = mutableListOf<Pair<String, String?>>()
        VivoTextBridgeService.executeModelCall(controller, secret, select,
            { config, messages, _ -> complete(config, messages) }) { code, text ->
            delivered.add(code to text)
        }
        assertEquals(1, delivered.size)
        ShadowLog.getLogsForTag("EtaVivoText").forEach {
            assertNull(it.throwable)
            assertFalse(it.msg.contains("private"))
        }
        return delivered.single()
    }

    @Test fun successStillMakesOneTextOnlyCallAndReturnsOnlyItsText() {
        var calls = 0
        val result = execute { config, messages ->
            calls++
            assertSame(modelConfig, config)
            assertEquals(1, messages.length())
            assertEquals("user", messages.getJSONObject(0).getString("role"))
            assertEquals(secret, messages.getJSONObject(0).getString("content"))
            assertEquals(2, messages.getJSONObject(0).length())
            "answer"
        }
        assertEquals(1, calls)
        assertEquals("OK" to "answer", result)
        assertEquals(listOf("v=1 stage=MODEL_STARTED n=1", "v=1 stage=MODEL_FINISHED n=2"), logs())
    }

    @Test fun modelFailureIsDiagnosedButOriginalFixedWireErrorIsUnchanged() {
        var calls = 0
        val result = execute { _, _ ->
            calls++
            throw AgentModelFailure("HTTP_401", false, secret, diagnostic = secret)
        }
        assertEquals(1, calls)
        assertEquals("MODEL_ERROR" to null, result)
        assertEquals(listOf("v=1 stage=MODEL_STARTED n=1",
            "v=1 stage=MODEL_FAILED n=2 error=HTTP_401 http_code=401 phase=MODEL_CALL"), logs())
    }

    @Test fun configNetworkFailureIsNotMislabelledAsBadConfiguration() {
        val result = execute(select = { throw UnknownHostException(secret) }) { _, _ ->
            fail("must not submit a model request"); "unused"
        }
        assertEquals("MODEL_ERROR" to null, result)
        assertEquals(listOf("v=1 stage=SERVICE_PREPARATION_FAILED n=1 error=DNS phase=SELECT_CONFIG"), logs())
    }

    @Test fun missingConfigRetainsNoModelAndDoesNotInvokeCompletion() {
        val result = execute(select = { null }) { _, _ -> fail("no model call"); "unused" }
        assertEquals("NO_MODEL" to null, result)
        assertEquals(listOf("v=1 stage=SERVICE_PREPARATION_FAILED n=1 error=CONFIG phase=SELECT_CONFIG"), logs())
    }

    @Test fun preCancelledControllerDoesNotReadConfigOrCallModel() {
        val controller = AgentRunController().apply { cancel() }
        val result = execute(controller, select = { fail("no config read"); null }) { _, _ ->
            fail("no model call"); "unused"
        }
        assertEquals("CANCELLED" to null, result)
        assertEquals(listOf("v=1 stage=SERVICE_PREPARATION_FAILED n=1 error=CANCEL phase=SELECT_CONFIG"), logs())
    }

    @Test fun cancellationAfterModelStartedHasFailureStageWithoutFinished() {
        val controller = AgentRunController()
        val result = execute(controller) { _, _ -> controller.cancel(); throw IllegalStateException(secret) }
        assertEquals("CANCELLED" to null, result)
        assertEquals(listOf("v=1 stage=MODEL_STARTED n=1",
            "v=1 stage=MODEL_FAILED n=2 error=CANCEL phase=MODEL_CALL"), logs())
    }

    @Test fun typedCancellationWithoutControllerCancellationDoesNotChangeWireSemantics() {
        val result = execute { _, _ -> throw CancellationException(secret) }
        assertEquals("MODEL_ERROR" to null, result)
        assertTrue(logs().last().contains("error=CANCEL phase=MODEL_CALL"))
    }

    @Test fun genericArgumentFailureIsNotInventedAsConfigOrHttpFailure() {
        val result = execute { _, _ -> throw IllegalArgumentException("161ms HTTP 400 $secret") }
        assertEquals("MODEL_ERROR" to null, result)
        assertEquals("v=1 stage=MODEL_FAILED n=2 error=UNKNOWN phase=MODEL_CALL", logs().last())
    }

    @Test fun oversizedResultStillFailsWithoutReturningContent() {
        val result = execute { _, _ -> "x".repeat(VivoTextBridgePolicy.MAX_RESULT + 1) }
        assertEquals("RESULT_TOO_LARGE" to null, result)
        assertEquals("v=1 stage=MODEL_FINISHED n=2", logs().last())
    }

    @Test fun fatalErrorIsRethrownUnchangedAndNeverDeliversSuccess() {
        val fatal = AssertionError(secret)
        val delivered = mutableListOf<Pair<String, String?>>()
        try {
            VivoTextBridgeService.executeModelCall(AgentRunController(), secret, { modelConfig },
                { _, _, _ -> throw fatal }, { code, text -> delivered.add(code to text) })
            fail("fatal error must propagate")
        } catch (caught: AssertionError) {
            assertSame(fatal, caught)
        }
        assertEquals(listOf("MODEL_ERROR" to null), delivered)
        assertEquals("v=1 stage=MODEL_FAILED n=2 error=UNKNOWN phase=MODEL_CALL", logs().last())
        ShadowLog.getLogsForTag("EtaVivoText").forEach { assertNull(it.throwable); assertFalse(it.msg.contains("private")) }
    }

    @Test fun exhaustedDiagnosticsBudgetCannotChangeModelResult() {
        repeat(200) { VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.entries[it % VivoBridgeDiagnostics.Stage.entries.size]) }
        assertEquals(80, logs().size)
        assertEquals("OK" to "answer", execute { _, _ -> "answer" })
        assertEquals("MODEL_ERROR" to null, execute { _, _ -> throw UnknownHostException(secret) })
        assertEquals(80, logs().size)
    }

    @Test fun serviceFinishLogsFixedTerminalOnceAndDoesNotAddMetadataToReply() {
        val serviceController = Robolectric.buildService(VivoTextBridgeService::class.java).create()
        try {
            val replies = mutableListOf<Message>()
            val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { replies.add(Message.obtain(msg)) }
            })
            val callType = VivoTextBridgeService::class.java.declaredClasses.single { it.simpleName == "Call" }
            val call = callType.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java, Messenger::class.java)
                .apply { isAccessible = true }.newInstance(10001, "fixture-id", reply)
            callType.getDeclaredField("timeout").apply { isAccessible = true }.set(call, Runnable {})
            callType.getDeclaredField("death").apply { isAccessible = true }.set(call, IBinder.DeathRecipient {})
            val finish = VivoTextBridgeService::class.java.getDeclaredMethod("finish", callType,
                String::class.java, String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            finish.invoke(serviceController.get(), call, "MODEL_ERROR", secret, true)
            finish.invoke(serviceController.get(), call, "OK", "must-not-send", true)
            ShadowLooper.idleMainLooper()
            assertEquals(1, replies.size)
            assertEquals(VivoTextBridgePolicy.RESULT, replies.single().what)
            assertEquals(setOf("request_id", "code"), replies.single().data.keySet())
            assertEquals("MODEL_ERROR", replies.single().data.getString("code"))
            assertEquals(listOf("v=1 stage=SERVICE_TERMINAL n=1 code=MODEL_ERROR"), logs())
        } finally {
            serviceController.destroy()
        }
    }

    @Test fun normalBuildDoesNotExposeUsableBinder() {
        val controller = Robolectric.buildService(VivoTextBridgeService::class.java).create()
        try {
            val service = controller.get()
            assertTrue(service.resources.getBoolean(R.bool.vivo_text_bridge_enabled))
            assertNull(service.onBind(Intent()))
        } finally {
            controller.destroy()
        }
    }

    @Test fun componentCanBeAddressedButConsentStillGatesBinding() {
        val controller = Robolectric.buildService(VivoTextBridgeService::class.java).create()
        try {
            val service = controller.get()
            @Suppress("DEPRECATION")
            val info = service.packageManager.getServiceInfo(
                ComponentName(service, VivoTextBridgeService::class.java),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertTrue(info.enabled)
            assertNull(service.onBind(Intent()))
            assertTrue(info.exported)
        } finally {
            controller.destroy()
        }
    }
}
