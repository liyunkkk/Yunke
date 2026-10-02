package io.github.mangi.eta.agent.vivo

import android.util.Log

/** Fixed-stage metadata only; no text, IDs, endpoints, config or exception details. */
internal object VivoBridgeDiagnostics {
    enum class Stage {
        BOOTSTRAP_ENTERED, BOOTSTRAP_PREINIT_SUCCEEDED, BOOTSTRAP_PREINIT_FAILED,
        BOOTSTRAP_ORIGINAL_RETURNED, BOOTSTRAP_ORIGINAL_THREW, BOOTSTRAP_CONTEXT_UNAVAILABLE,
        BOOTSTRAP_IDENTITY_REJECTED, BOOTSTRAP_IDENTITY_QUERY_FAILED,
        BOOTSTRAP_ALREADY_CLAIMED, BOOTSTRAP_INIT_FAILED,
        QUERY_ENTERED, QUERY_GATE_CLOSED, QUERY_NON_REMOTE, QUERY_IDS_REJECTED,
        QUERY_REFLECTION_FAILED, QUERY_BEGUN,
        MAPPER_ENTERED, MAPPER_GATE_CLOSED, MAPPER_SHAPE_REJECTED, MAPPER_PROMPT_REJECTED,
        MAPPER_IDS_REJECTED, MAPPER_REFLECTION_FAILED, MAPPER_TURN_MISSING, MAPPER_BOUND,
        OUTBOUND_ENTERED, OUTBOUND_RECEIPT_MISSING, OUTBOUND_LINK_MISMATCH, DISPATCH_FAILED,
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

    private val budget = VivoDiagnosticBudget(Stage.entries.size, totalLimit = 80, perStageLimit = 4)
    fun record(stage: Stage, failure: Failure? = null) {
        // Bound noisy stages before spending the shared process budget. All diagnostics
        // are best effort: they must not mask vendor exceptions or affect owned suppression.
        runCatching {
            val count = budget.claim(stage.ordinal) ?: return@runCatching
            val category = failure?.let { " failure=${it.name}" }.orEmpty()
            Log.i("EtaVivoText", "v=1 stage=${stage.name} n=$count$category")
        }
    }
}
