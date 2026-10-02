package io.github.mangi.eta.agent.vivo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.security.MessageDigest

/** Runs in copilot. No keys, provider settings, native-payload parsing or automatic retry. */
internal class VivoTextBridgeClient(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val state = VivoClientState()
    private var active: Work? = null
    private var closed = false

    private class Work(val ticket: VivoClientState.Ticket, val callback: (String, String?) -> Unit) {
        var service: Messenger? = null
        var binder: IBinder? = null
        var expectedUid = -1
        var registered = false
        lateinit var connection: ServiceConnection
        lateinit var death: IBinder.DeathRecipient
        lateinit var timeout: Runnable
    }

    fun submit(id: String, prompt: String, stillOwner: () -> Boolean, callback: (String, String?) -> Unit) {
        fun owns() = runCatching(stillOwner).getOrDefault(false)
        main.post {
            if (!owns()) { deliver(callback, "CANCELLED", null); return@post }
            if (closed) { deliver(callback, "CLOSED", null); return@post }
            if (VivoTextBridgePolicy.command(VivoTextBridgePolicy.REQUEST,
                    mapOf("request_id" to id, "prompt" to prompt)) == null) {
                deliver(callback, "BAD_REQUEST", null); return@post
            }
            val ticket = state.begin(id)
            if (ticket == null) { deliver(callback, "BUSY_OR_REPLAY", null); return@post }
            val work = Work(ticket, callback)
            active = work
            val uid = trustedEtaUid()
            work.expectedUid = uid ?: -1
            work.timeout = Runnable { cancelAndFinish(work, "TIMEOUT") }
            work.death = IBinder.DeathRecipient { main.post { finish(work, "DISCONNECTED") } }
            work.connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (!state.current(ticket)) return
                    if (name != COMPONENT || work.expectedUid < 0) {
                        finish(work, "UNTRUSTED_PEER"); return
                    }
                    try {
                        work.binder = binder
                        binder.linkToDeath(work.death, 0)
                        work.service = Messenger(binder)
                        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.CLIENT_BOUND)
                        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                            override fun handleMessage(msg: Message) { receive(work, msg) }
                        })
                        // Re-check ownership after asynchronous binding, not merely before queuing.
                        if (!state.markSent(ticket, owns())) { cancelAndFinish(work, "CANCELLED"); return }
                        // SENT is now committed; a send exception never authorizes replay.
                        work.service!!.send(Message.obtain(null, VivoTextBridgePolicy.REQUEST).apply {
                            replyTo = reply
                            data = Bundle().apply { putString("request_id", id); putString("prompt", prompt) }
                        })
                    } catch (_: Exception) { finish(work, "UNAVAILABLE") }
                }
                override fun onServiceDisconnected(name: ComponentName) { finish(work, "DISCONNECTED") }
                override fun onBindingDied(name: ComponentName) { finish(work, "DISCONNECTED") }
                override fun onNullBinding(name: ComponentName) { finish(work, "DISABLED") }
            }
            if (uid == null) { finish(work, "UNTRUSTED_PEER"); return@post }
            try {
                main.postDelayed(work.timeout, VivoTextBridgePolicy.TIMEOUT_MS + 5_000)
                work.registered = true
                val bound = context.bindService(Intent().setComponent(COMPONENT), work.connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    work.registered = false
                    finish(work, "UNAVAILABLE")
                }
            } catch (_: Exception) { finish(work, "UNAVAILABLE") }
        }
    }

    fun cancel(id: String) {
        main.post { active?.takeIf { it.ticket.id == id }?.let { cancelAndFinish(it, "CANCELLED") } }
    }

    fun close() {
        main.post {
            active?.let { cancelAndFinish(it, "CLOSED") }
            closed = true
            state.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun receive(work: Work, msg: Message) {
        if (!state.current(work.ticket) || msg.sendingUid != work.expectedUid) return
        if (msg.what != VivoTextBridgePolicy.RESULT && msg.what != VivoTextBridgePolicy.REJECTED) return
        try {
            val data = msg.data
            val keys = data.keySet()
            if (keys.size !in 2..3 || keys.any { it !in setOf("request_id", "code", "text") }) {
                cancelAndFinish(work, "BAD_RESPONSE"); return
            }
            val id = data.get("request_id") as? String
            if (id != work.ticket.id) return
            val code = data.get("code") as? String
            val text = data.get("text") as? String
            if (code == null || !CODE.matches(code) ||
                code == "OK" && (msg.what != VivoTextBridgePolicy.RESULT || text.isNullOrBlank() ||
                    text.length > VivoTextBridgePolicy.MAX_RESULT)) {
                cancelAndFinish(work, "BAD_RESPONSE"); return
            }
            finish(work, code, if (code == "OK") text else null)
        } catch (_: Exception) { cancelAndFinish(work, "BAD_RESPONSE") }
    }

    private fun cancelAndFinish(work: Work, code: String) {
        if (!state.current(work.ticket)) return
        try {
            work.service?.send(Message.obtain(null, VivoTextBridgePolicy.CANCEL).apply {
                // Service requires replyTo for all commands; use a non-delivering sink.
                replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) { }
                })
                data = Bundle().apply { putString("request_id", work.ticket.id) }
            })
        } catch (_: Exception) { }
        finish(work, code)
    }

    private fun finish(work: Work, code: String, text: String? = null) {
        if (!state.finish(work.ticket)) return
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.CLIENT_TERMINAL)
        main.removeCallbacks(work.timeout)
        try { work.binder?.unlinkToDeath(work.death, 0) } catch (_: Exception) { }
        if (work.registered) {
            work.registered = false
            try { context.unbindService(work.connection) } catch (_: Exception) { }
        }
        work.service = null
        work.binder = null
        if (active === work) active = null
        deliver(work.callback, code, text)
    }

    private fun deliver(callback: (String, String?) -> Unit, code: String, text: String?) {
        try { callback(code, text) } catch (_: Exception) { /* Never retry or leak callback data. */ }
    }

    private fun trustedEtaUid(): Int? = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(ETA_PACKAGE,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val uid = info.applicationInfo?.uid ?: -1
        val signers = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.orEmpty()
        val service = pm.getServiceInfo(COMPONENT, PackageManager.ComponentInfoFlags.of(0))
        uid.takeIf { it >= 10000 && pm.getPackagesForUid(it)?.toList() == listOf(ETA_PACKAGE) &&
            signers == listOf(ETA_SIGNER) && service.enabled && service.exported &&
            service.applicationInfo?.uid == it && service.packageName == ETA_PACKAGE }
    } catch (_: Exception) { null }

    companion object {
        const val ETA_PACKAGE = "io.github.mangi.eta"
        const val ETA_SIGNER = "1e1c8731bd2a7fab53b941cd9869884e7756bed96fadd10f322e70d760d1d193"
        private val COMPONENT = ComponentName(ETA_PACKAGE, "$ETA_PACKAGE.agent.vivo.VivoTextBridgeService")
        private val CODE = Regex("[A-Z_]{1,40}")
    }
}
