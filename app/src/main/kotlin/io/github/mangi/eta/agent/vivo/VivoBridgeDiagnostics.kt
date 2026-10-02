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
        SERVICE_PREPARATION_FAILED, MODEL_FAILED,
    }
    enum class Failure { CLASS, METHOD, FIELD, LINKAGE, REGISTRATION, OTHER }
    enum class FailurePhase {
        SELECT_CONFIG, PREPARE_REQUEST, MODEL_CALL, RESULT_CHECK,
        CONFIG_VALIDATION, CANCELLATION_CHECK, REQUEST_PREPARATION, PROVIDER_CALL,
        STOP_REASON_VALIDATION, TOOL_CALL_VALIDATION, BODY_VALIDATION,
    }
    enum class TerminalCode {
        OK, NO_MODEL, RESULT_TOO_LARGE, CANCELLED, MODEL_ERROR, UNAVAILABLE,
        TIMEOUT, CALLER_GONE, SERVICE_STOPPED, UNKNOWN;

        companion object {
            fun fromWire(code: String): TerminalCode = entries.firstOrNull { it.name == code } ?: UNKNOWN
        }
    }

    // Closed vocabulary only: never include a native field value, class name or exception detail.
    enum class Reason {
        MAPPED_NULL, MAPPED_TYPE, REQUEST_NULL, REQUEST_TYPE, MODEL_NULL, MODEL_TYPE,
        DIALOG_ID_TYPE, CONVERSATION_ID_TYPE, DIALOG_ID, CONVERSATION_ID,
        AGENT_ID_TYPE, INPUT_TYPE_TYPE, BIZ_SOURCE_TYPE, RENDER_TEXT_TYPE, SHORTCUT_TYPE,
        REGENERATE_TYPE, SKIP_REMOTE_TYPE, RECOMMENDED_TYPE,
        AGENT_ID, INPUT_TYPE, BIZ_SOURCE, RENDER_TEXT, SHORTCUT, REGENERATE, SKIP_REMOTE,
        RECOMMENDED, SPECIALIZED,
        ATTACHMENT, CAMERA_CONTEXT, PS_AGENT_CONTEXT, TWS_NOTIFICATION_CONTEXT, EXTRA_PARAMS,
        SCHEDULE_CONTEXT_TYPE, SCHEDULE_CONTEXT, BOT_TYPE_TYPE, BOT_TYPE,
        INTENTIONS_TYPE, INTENTION_TEXT_TYPE, INTENTIONS,
        NEW_QUERY_PARAMS_TYPE, NEW_QUERY_PARAMS, DISPLAY_QUERY_TYPE, SERVER_QUERY_TYPE, PROMPT,
    }

    fun failureCategory(error: Throwable): Failure = when (error) {
        is ClassNotFoundException -> Failure.CLASS
        is NoSuchMethodException -> Failure.METHOD
        is NoSuchFieldException -> Failure.FIELD
        is LinkageError -> Failure.LINKAGE
        else -> Failure.OTHER
    }

    private val budget = VivoDiagnosticBudget(Stage.entries.size, totalLimit = 80, perStageLimit = 4)
    fun record(
        stage: Stage,
        failure: Failure? = null,
        reason: Reason? = null,
        modelFailure: VivoModelFailureClassifier.Classification? = null,
        phase: FailurePhase? = null,
        terminalCode: TerminalCode? = null,
    ) {
        // Bound noisy stages before spending the shared process budget. All diagnostics
        // are best effort: they must not mask vendor exceptions or affect owned suppression.
        runCatching {
            val count = budget.claim(stage.ordinal) ?: return@runCatching
            val category = failure?.let { " failure=${it.name}" }.orEmpty()
            val rejection = reason?.let { " reason=${it.name}" }.orEmpty()
            val modelCategory = modelFailure?.let { " error=${it.category.name}" }.orEmpty()
            val http = modelFailure?.httpCode?.takeIf { it in 100..599 }
                ?.let { " http_code=$it" }.orEmpty()
            val modelCode = modelFailure?.modelCode?.let { " model_code=${it.name}" }.orEmpty()
            val location = phase?.let { " phase=${it.name}" }.orEmpty()
            val terminal = terminalCode?.let { " code=${it.name}" }.orEmpty()
            Log.i("EtaVivoText", "v=1 stage=${stage.name} n=$count$category$rejection$modelCategory$http$modelCode$location$terminal")
        }
    }
}
