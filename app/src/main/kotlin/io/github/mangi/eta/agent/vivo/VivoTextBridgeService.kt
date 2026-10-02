package io.github.mangi.eta.agent.vivo

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.VivoBridgeConsent
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ModelFeatureCompletion
import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Disabled by default; a text-only endpoint, not an Agent or tool execution entry point. */
class VivoTextBridgeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) = receive(msg)
    })
    @Volatile private var active: Call? = null
    private var destroyed = false

    private class Call(val uid: Int, val id: String, val reply: Messenger) {
        val controller = AgentRunController()
        val terminal = AtomicBoolean(false)
        lateinit var thread: Thread
        lateinit var death: IBinder.DeathRecipient
        lateinit var timeout: Runnable
    }

    private val revokeImmediately: () -> Unit = {
        // Capture the revoked call now; a queued cleanup must never cancel a later grant's call.
        active?.let { revoked ->
            revoked.controller.cancel()
            main.post { if (active === revoked) stop(revoked, "CANCELLED") }
        }
    }
    private val consentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.Keys.VIVO_TEXT_BRIDGE && !VivoBridgeConsent.localEnabled()) {
            revokeImmediately()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(applicationContext)
        VivoBridgeConsent.addRevocationListener(revokeImmediately)
        Prefs.localAgentPreferences()?.registerOnSharedPreferenceChangeListener(consentListener)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (enabled()) messenger.binder else null

    private fun enabled() = VivoTextBridgePolicy.deviceAllowed(
        resources.getBoolean(R.bool.vivo_text_bridge_enabled) && VivoBridgeConsent.localEnabled(),
        Build.MODEL, Build.VERSION.SDK_INT,
    )

    private fun authorized(uid: Int): Boolean = try {
        if (!enabled()) false else {
            val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            if (uid !in 10000..19999 || packages != listOf(VivoTextBridgePolicy.PACKAGE)) false else {
                val info = packageManager.getPackageInfo(VivoTextBridgePolicy.PACKAGE,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                val signers = info.signingInfo?.apkContentsSigners?.map { signer ->
                    MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }.orEmpty()
                VivoTextBridgePolicy.callerAllowed(uid, packages, info.longVersionCode, signers)
            }
        }
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun receive(msg: Message) {
        // sendingUid is supplied by Messenger/Binder, never by the caller's Bundle.
        if (destroyed || !authorized(msg.sendingUid)) return
        val reply = msg.replyTo ?: return
        val command = try {
            val data = msg.data
            if (data.size() > 2) null else VivoTextBridgePolicy.command(msg.what,
                data.keySet().associateWith { data.get(it) })
        } catch (_: Exception) { null }
        if (command == null) {
            send(reply, VivoTextBridgePolicy.REJECTED, "", "BAD_REQUEST")
            return
        }
        if (msg.what == VivoTextBridgePolicy.CANCEL) {
            val call = active
            if (call != null && call.uid == msg.sendingUid && call.id == command.id) {
                stop(call, "CANCELLED")
            } else send(reply, VivoTextBridgePolicy.REJECTED, command.id, "NOT_ACTIVE")
            return
        }
        val admission = ledger.admit(msg.sendingUid, command.id)
        if (admission != VivoTextBridgeLedger.Admission.ACCEPTED) {
            send(reply, VivoTextBridgePolicy.REJECTED, command.id, admission.name)
            return
        }
        val call = Call(msg.sendingUid, command.id, reply)
        active = call
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.SERVICE_ACCEPTED)
        call.timeout = Runnable { stop(call, "TIMEOUT") }
        call.death = IBinder.DeathRecipient { main.post { stop(call, "CALLER_GONE", notify = false) } }
        call.thread = Thread({
            executeModelCall(call.controller, command.prompt,
                selectConfig = { runBlocking { VivoTextModelGateway.selectedConfig() } },
                complete = { config, messages, onFailurePhase ->
                    ModelFeatureCompletion.complete(config, messages, call.controller,
                        sessionId = "vivo-text-${call.id}", timeoutMs = VivoTextBridgePolicy.TIMEOUT_MS,
                        outputLimit = 2048, onFailurePhase = onFailurePhase)
                },
                deliver = { resultCode, resultText ->
                    main.post {
                        finish(call, resultCode, resultText)
                        if (active === call) active = null
                        ledger.release(call.uid, call.id)
                    }
                })
        }, "eta-vivo-text").apply { isDaemon = true }
        try {
            reply.binder.linkToDeath(call.death, 0)
            main.postDelayed(call.timeout, VivoTextBridgePolicy.TIMEOUT_MS)
            call.thread.start()
        } catch (_: Exception) {
            finish(call, "UNAVAILABLE")
            active = null
            ledger.release(call.uid, call.id)
        }
    }

    private fun stop(call: Call, code: String, notify: Boolean = true) {
        if (call.terminal.get()) return
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.SERVICE_CANCELLED)
        call.controller.cancel()
        call.thread.interrupt()
        finish(call, code, notify = notify)
        // Keep the slot busy until the worker actually exits, even after cancellation.
    }

    private fun finish(call: Call, code: String, text: String? = null, notify: Boolean = true) {
        if (!call.terminal.compareAndSet(false, true)) return
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.SERVICE_TERMINAL,
            terminalCode = VivoBridgeDiagnostics.TerminalCode.fromWire(code))
        main.removeCallbacks(call.timeout)
        try { call.reply.binder.unlinkToDeath(call.death, 0) } catch (_: Exception) { }
        if (notify && !destroyed) send(call.reply, VivoTextBridgePolicy.RESULT, call.id, code, text)
    }

    private fun send(reply: Messenger, what: Int, id: String, code: String, text: String? = null) {
        try {
            reply.send(Message.obtain(null, what).apply {
                data = Bundle().apply {
                    putString("request_id", id)
                    putString("code", code)
                    if (code == "OK" && text != null) putString("text", text)
                }
            })
        } catch (_: Exception) { /* No raw exception, prompt, endpoint or key logging. */ }
    }

    override fun onDestroy() {
        destroyed = true
        VivoBridgeConsent.removeRevocationListener(revokeImmediately)
        Prefs.localAgentPreferences()?.unregisterOnSharedPreferenceChangeListener(consentListener)
        active?.let { stop(it, "SERVICE_STOPPED", notify = false) }
        super.onDestroy()
    }

    companion object {
        private val ledger = VivoTextBridgeLedger()

        // The same worker boundary is exercised without Binder or a real model in tests.
        // Diagnostics never select a provider, retry a request, or alter the wire result.
        internal fun executeModelCall(
            controller: AgentRunController,
            prompt: String?,
            selectConfig: () -> AgentModelClient.ModelConfig?,
            complete: (AgentModelClient.ModelConfig, JSONArray, (ModelFeatureCompletion.FailurePhase) -> Unit) -> String,
            deliver: (String, String?) -> Unit,
        ) {
            var code = "MODEL_ERROR"
            var answer: String? = null
            var phase = VivoBridgeDiagnostics.FailurePhase.SELECT_CONFIG
            var modelStarted = false
            try {
                controller.throwIfCancelled()
                val config = selectConfig()
                controller.throwIfCancelled()
                if (config == null) {
                    code = "NO_MODEL"
                    runCatching {
                        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.SERVICE_PREPARATION_FAILED,
                            modelFailure = VivoModelFailureClassifier.Classification(VivoModelFailureClassifier.Category.CONFIG),
                            phase = phase)
                    }
                } else {
                    phase = VivoBridgeDiagnostics.FailurePhase.PREPARE_REQUEST
                    // No user-supplied system prompt, history, model override, URL or credentials.
                    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt!!))
                    phase = VivoBridgeDiagnostics.FailurePhase.MODEL_CALL
                    modelStarted = true
                    VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_STARTED)
                    val text = complete(config, messages) { failedPhase ->
                        phase = when (failedPhase) {
                            ModelFeatureCompletion.FailurePhase.CONFIG_VALIDATION -> VivoBridgeDiagnostics.FailurePhase.CONFIG_VALIDATION
                            ModelFeatureCompletion.FailurePhase.CANCELLATION_CHECK -> VivoBridgeDiagnostics.FailurePhase.CANCELLATION_CHECK
                            ModelFeatureCompletion.FailurePhase.REQUEST_PREPARATION -> VivoBridgeDiagnostics.FailurePhase.REQUEST_PREPARATION
                            ModelFeatureCompletion.FailurePhase.PROVIDER_CALL -> VivoBridgeDiagnostics.FailurePhase.PROVIDER_CALL
                            ModelFeatureCompletion.FailurePhase.STOP_REASON_VALIDATION -> VivoBridgeDiagnostics.FailurePhase.STOP_REASON_VALIDATION
                            ModelFeatureCompletion.FailurePhase.TOOL_CALL_VALIDATION -> VivoBridgeDiagnostics.FailurePhase.TOOL_CALL_VALIDATION
                            ModelFeatureCompletion.FailurePhase.BODY_VALIDATION -> VivoBridgeDiagnostics.FailurePhase.BODY_VALIDATION
                        }
                    }
                    VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_FINISHED)
                    phase = VivoBridgeDiagnostics.FailurePhase.RESULT_CHECK
                    if (text.length > VivoTextBridgePolicy.MAX_RESULT) code = "RESULT_TOO_LARGE"
                    else { answer = text; code = "OK" }
                }
            } catch (error: Throwable) {
                code = if (error is Exception && controller.isCancelled) "CANCELLED" else "MODEL_ERROR"
                answer = null
                // Even diagnostics failures cannot mask the original result or fatal throwable.
                runCatching {
                    VivoBridgeDiagnostics.record(
                        if (modelStarted) VivoBridgeDiagnostics.Stage.MODEL_FAILED
                        else VivoBridgeDiagnostics.Stage.SERVICE_PREPARATION_FAILED,
                        modelFailure = VivoModelFailureClassifier.classify(error, controller.isCancelled),
                        phase = phase)
                }
                // Preserve the former uncaught-Error behavior; never turn Error into success.
                if (error !is Exception) throw error
            } finally {
                deliver(code, answer)
            }
        }
    }
}
