package io.github.mangi.eta.agent.vivo

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** Fixed-stage metadata only; no text, IDs, endpoints, config or exception details. */
internal object VivoBridgeDiagnostics {
    enum class Stage {
        BOOTSTRAP_ENTERED, BOOTSTRAP_PREINIT_SUCCEEDED, BOOTSTRAP_PREINIT_FAILED,
        BOOTSTRAP_ORIGINAL_RETURNED, BOOTSTRAP_ORIGINAL_THREW, BOOTSTRAP_CONTEXT_UNAVAILABLE,
        BOOTSTRAP_IDENTITY_REJECTED, BOOTSTRAP_IDENTITY_QUERY_FAILED,
        BOOTSTRAP_ALREADY_CLAIMED, BOOTSTRAP_INIT_FAILED,
        HOOK_READY, DISPATCH_CLAIMED, CLIENT_BOUND, CLIENT_TERMINAL,
        SERVICE_ACCEPTED, MODEL_STARTED, MODEL_FINISHED, SERVICE_TERMINAL,
        OWNER_CANCELLED, SERVICE_CANCELLED, NATIVE_REPLY_ENQUEUED, NATIVE_SINK_FAILED,
    }
    enum class Failure { CLASS, METHOD, FIELD, LINKAGE, REGISTRATION, OTHER }

    fun failureCategory(error: Throwable): Failure = when (error) {
        is ClassNotFoundException -> Failure.CLASS
        is NoSuchMethodException -> Failure.METHOD
        is NoSuchFieldException -> Failure.FIELD
        is LinkageError -> Failure.LINKAGE
        else -> Failure.OTHER
    }

    private val lines = AtomicInteger()
    fun record(stage: Stage, failure: Failure? = null) {
        val count = lines.incrementAndGet()
        // Diagnostics must never prevent proceed(), mask its exception or alter its return value.
        if (count <= 80) runCatching {
            val category = failure?.let { " failure=${it.name}" }.orEmpty()
            Log.i("EtaVivoText", "v=1 stage=${stage.name} n=$count$category")
        }
    }
}
