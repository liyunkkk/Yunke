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
    private var active: Call? = null
    private var destroyed = false

    private class Call(val uid: Int, val id: String, val reply: Messenger) {
        val controller = AgentRunController()
        val terminal = AtomicBoolean(false)
        lateinit var thread: Thread
        lateinit var death: IBinder.DeathRecipient
        lateinit var timeout: Runnable
    }

    private val consentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.Keys.VIVO_TEXT_BRIDGE && !VivoBridgeConsent.localEnabled()) {
            main.post { active?.let { stop(it, "CANCELLED") } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(applicationContext)
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
            var code = "MODEL_ERROR"
            var answer: String? = null
            try {
                call.controller.throwIfCancelled()
                val config = runBlocking { VivoTextModelGateway.selectedConfig() }
                call.controller.throwIfCancelled()
                if (config == null) code = "NO_MODEL" else {
                    // No user-supplied system prompt, history, model override, URL or credentials.
                    val messages = JSONArray().put(JSONObject().put("role", "user")
                        .put("content", command.prompt!!))
                    VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_STARTED)
                    val text = ModelFeatureCompletion.complete(config, messages, call.controller,
                        sessionId = "vivo-text-${call.id}", timeoutMs = VivoTextBridgePolicy.TIMEOUT_MS,
                        outputLimit = 2048)
                    VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_FINISHED)
                    if (text.length > VivoTextBridgePolicy.MAX_RESULT) code = "RESULT_TOO_LARGE"
                    else { answer = text; code = "OK" }
                }
            } catch (_: Exception) {
                code = if (call.controller.isCancelled) "CANCELLED" else "MODEL_ERROR"
            } finally {
                val resultCode = code
                val resultText = answer
                main.post {
                    finish(call, resultCode, resultText)
                    if (active === call) active = null
                    ledger.release(call.uid, call.id)
                }
            }
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
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.SERVICE_TERMINAL)
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
        Prefs.localAgentPreferences()?.unregisterOnSharedPreferenceChangeListener(consentListener)
        active?.let { stop(it, "SERVICE_STOPPED", notify = false) }
        super.onDestroy()
    }

    companion object { private val ledger = VivoTextBridgeLedger() }
}
