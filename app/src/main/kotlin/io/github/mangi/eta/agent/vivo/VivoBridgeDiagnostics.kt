package io.github.mangi.eta.agent.vivo

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** Fixed-stage metadata only; no text, IDs, endpoints, config or exception details. */
internal object VivoBridgeDiagnostics {
    enum class Stage {
        HOOK_READY, DISPATCH_CLAIMED, CLIENT_BOUND, CLIENT_TERMINAL,
        SERVICE_ACCEPTED, MODEL_STARTED, MODEL_FINISHED, SERVICE_TERMINAL,
        OWNER_CANCELLED, SERVICE_CANCELLED, NATIVE_REPLY_ENQUEUED, NATIVE_SINK_FAILED,
    }
    private val lines = AtomicInteger()
    fun record(stage: Stage) {
        val count = lines.incrementAndGet()
        if (count <= 80) Log.i("EtaVivoText", "v=1 stage=${stage.name} n=$count")
    }
}
