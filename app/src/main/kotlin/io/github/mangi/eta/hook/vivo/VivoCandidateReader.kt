package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.vivo.VivoBridgeDiagnostics.Reason

/** The production mapper reader, isolated from Android and ownership/dispatch state. */
internal class VivoCandidateReader(
    private val request: Class<*>, private val model: Class<*>, private val payload: Class<*>,
    private val intentions: Class<*>, private val newQueryParams: Class<*>,
) {
    sealed interface Result
    data class Accepted(val dialog: String, val conversation: String, val prompt: String) : Result
    data class Rejected(val reason: Reason) : Result

    private fun getters(owner: Class<*>, vararg names: String) = names.associateWith {
        owner.getDeclaredMethod(it).apply { isAccessible = true }
    }
    // Preserve the actual native ABI. In particular, do not constrain getModel's declared return type.
    private val requestGetters = getters(request, "getModel", "getDialogId", "getConversationId")
    private val modelGetters = getters(model, "getServerQuery", "getDisplayQuery", "getAgentId", "getInputType", "getBizSource",
        "getRenderText", "getShortcut", "getRegenerate", "getSkipRemote", "getFromRecommend", "getAttachmentQueryModel",
        "getCameraContext", "getPsAgentContext", "getTwsNotificationContext", "getExtraParams", "getScheduleContext",
        "getBotType", "getIntentions", "getNewQueryParams")
    private val intentFields = listOf("first", "second", "third").map {
        intentions.getDeclaredField(it).apply { isAccessible = true }
    }
    private val extraQuery = newQueryParams.getDeclaredField("params").apply { isAccessible = true }

    // A local, detail-free control result; reflective invocation failures still reach the hook's
    // existing MAPPER_REFLECTION_FAILED handler. No native error/message is wrapped or logged here.
    private class InvalidType(val reason: Reason) : RuntimeException(null, null, false, false)
    private fun nullableString(value: Any?, reason: Reason): String? {
        if (value != null && value !is String) throw InvalidType(reason)
        return value as String?
    }
    private inline fun <reified T : Any> required(value: Any?, reason: Reason): T {
        if (value !is T) throw InvalidType(reason)
        return value
    }

    fun read(mapped: Any?, value: Any?, requirePrefix: Boolean): Result = try {
        readCandidate(mapped, value, requirePrefix)
    } catch (invalid: InvalidType) {
        Rejected(invalid.reason)
    }

    // Deterministic first rejection: object gates, IDs, scalar types, policy, specialized
    // fields in the order below, then prompt. Return one result; never log per field.
    private fun readCandidate(mapped: Any?, value: Any?, requirePrefix: Boolean): Result {
        if (mapped == null) return Rejected(Reason.MAPPED_NULL)
        if (!payload.isInstance(mapped)) return Rejected(Reason.MAPPED_TYPE)
        if (value == null) return Rejected(Reason.REQUEST_NULL)
        if (!request.isInstance(value)) return Rejected(Reason.REQUEST_TYPE)
        val modelValue = requestGetters.getValue("getModel").invoke(value)
            ?: return Rejected(Reason.MODEL_NULL)
        if (!model.isInstance(modelValue)) return Rejected(Reason.MODEL_TYPE)
        val dialog = nullableString(requestGetters.getValue("getDialogId").invoke(value), Reason.DIALOG_ID_TYPE)
        val conversation = nullableString(requestGetters.getValue("getConversationId").invoke(value), Reason.CONVERSATION_ID_TYPE)
        if (dialog == null || !VivoNativePolicy.validId(dialog)) return Rejected(Reason.DIALOG_ID)
        if (conversation == null || !VivoNativePolicy.validId(conversation)) return Rejected(Reason.CONVERSATION_ID)
        fun get(name: String) = modelGetters.getValue(name).invoke(modelValue)
        val shape = VivoNativePolicy.Shape(
            nullableString(get("getAgentId"), Reason.AGENT_ID_TYPE),
            required<Int>(get("getInputType"), Reason.INPUT_TYPE_TYPE),
            nullableString(get("getBizSource"), Reason.BIZ_SOURCE_TYPE),
            required<Boolean>(get("getRenderText"), Reason.RENDER_TEXT_TYPE),
            required<Boolean>(get("getShortcut"), Reason.SHORTCUT_TYPE),
            required<Boolean>(get("getRegenerate"), Reason.REGENERATE_TYPE),
            required<Boolean>(get("getSkipRemote"), Reason.SKIP_REMOTE_TYPE),
            required<Boolean>(get("getFromRecommend"), Reason.RECOMMENDED_TYPE),
            specialized = false, // Detailed specialized checks below; none are bypassed on acceptance.
        )
        VivoNativePolicy.rejection(shape)?.let { return Rejected(it) }
        for ((name, reason) in listOf(
            "getAttachmentQueryModel" to Reason.ATTACHMENT,
            "getCameraContext" to Reason.CAMERA_CONTEXT,
            "getPsAgentContext" to Reason.PS_AGENT_CONTEXT,
            "getTwsNotificationContext" to Reason.TWS_NOTIFICATION_CONTEXT,
            "getExtraParams" to Reason.EXTRA_PARAMS,
        )) {
            if (get(name) != null) return Rejected(reason)
        }
        if (!nullableString(get("getScheduleContext"), Reason.SCHEDULE_CONTEXT_TYPE).isNullOrBlank()) {
            return Rejected(Reason.SCHEDULE_CONTEXT)
        }
        val botType = nullableString(get("getBotType"), Reason.BOT_TYPE_TYPE)
        // Native manual text uses exact "main"; preserve null/blank without normalizing other types.
        if (!botType.isNullOrBlank() && botType != "main") {
            return Rejected(Reason.BOT_TYPE)
        }
        get("getIntentions")?.let { obj ->
            if (!intentions.isInstance(obj)) return Rejected(Reason.INTENTIONS_TYPE)
            for (field in intentFields) {
                if (!nullableString(field.get(obj), Reason.INTENTION_TEXT_TYPE).isNullOrBlank()) {
                    return Rejected(Reason.INTENTIONS)
                }
            }
        }
        get("getNewQueryParams")?.let { obj ->
            if (!newQueryParams.isInstance(obj)) return Rejected(Reason.NEW_QUERY_PARAMS_TYPE)
            // Native params is nullable JsonObject, not String. Even an empty object stays rejected.
            if (extraQuery.get(obj) != null) return Rejected(Reason.NEW_QUERY_PARAMS)
        }
        val display = nullableString(get("getDisplayQuery"), Reason.DISPLAY_QUERY_TYPE)
        val server = nullableString(get("getServerQuery"), Reason.SERVER_QUERY_TYPE)
        val visible = display?.takeIf { it.isNotBlank() } ?: server
        val prompt = VivoNativePolicy.prompt(visible, requirePrefix) ?: return Rejected(Reason.PROMPT)
        return Accepted(dialog, conversation, prompt)
    }
}
