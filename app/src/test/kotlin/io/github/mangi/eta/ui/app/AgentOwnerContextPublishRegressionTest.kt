package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentOwnerContextState
import io.github.mangi.eta.ui.model.RequestOverheadCalibration
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class AgentOwnerContextPublishRegressionTest {
    @Test fun signaturesRemainByteForByteCompatibleForStrictAndCustomActualRoutes() = withApp { app, provider ->
        val state = AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = false,
            thinkingEnabled = false, providerId = "p", modelId = "m")
        assertEquals(RequestOverheadCalibration.routeSignature(provider, provider.models.single()),
            call(app, "strictRouteSignature", state))
        assertEquals(call(app, "strictRouteSignature", state), call(app, "contextRouteSignature", state))
        val custom = provider.copy(apiKey = "test-only-key", systemPrompt = "test prompt", sessionGatewayJson = "{}",
            customHeaders = listOf(CustomHeader("x-test", "value")),
            customBody = listOf(CustomBody("test", JsonPrimitive(1))),
            models = listOf(provider.models.single().copy(customBody = listOf(CustomBody("model-test", JsonPrimitive(2))))))
        call(app, "updateSelectionProviders", listOf(custom))
        assertNull(call(app, "strictRouteSignature", state))
        repeat(10) { assertEquals(originalActualSignature(custom), call(app, "contextRouteSignature", state)) }
        val changed = custom.copy(customHeaders = listOf(CustomHeader("x-test", "changed")))
        call(app, "updateSelectionProviders", listOf(changed))
        assertEquals(originalActualSignature(changed), call(app, "contextRouteSignature", state))
        assertNotEquals(originalActualSignature(custom), call(app, "contextRouteSignature", state))
    }

    @Test fun childNoOpUsageSelectionHideAndHiddenRosterKeepParentReceiptAndMessagesUntouched() = withApp { app, _ ->
        val id = "owner"
        set(app, "selectedConversationId", id)
        val source = AgentChatHomeUiState(input = "", isStreaming = false,
            thinkingEnabled = false, providerId = "p", modelId = "m", contextHasStarted = true,
            messages = listOf(UserMessageUi("u", "question"), AgentMessageUi("a", "answer")),
            livePromptTokens = 999, cloudHistoryTokens = 700, cloudRequestOverheadTokens = 50,
            contextBudgetReceiptTokens = 999, isWaitingForAnswer = true)
        val route = call(app, "contextRouteSignature", source) as String
        // Intentionally keep the waiting bit despite this message list: child-only publication
        // must not scan/reproject messages. The normal three-argument API remains below.
        call(app, "updateConversationProjected", id, source.copy(cloudRouteSignature = route), false, false)
        val initial = state(app, id)
        var now = 1000L
        val reducer = AgentOwnerContextState(id) { now }
        @Suppress("UNCHECKED_CAST")
        (get(app, "ownerContexts") as MutableMap<String, AgentOwnerContextState>)[id] = reducer
        var child = SubAgentContextStats("task", 1, "research", "model", "Model", "Provider",
            contextWindow = 10000, contextTokens = 100, statusVersion = 1)
        fun refresh(revision: Long, version: Long = child.statusVersion) {
            reducer.refresh(id, listOf(AgentOwnerContextState.TaskSnapshot(child, revision, version)))
            call(app, "publishOwnerContext", id)
        }
        fun assertParentUntouched() {
            val current = state(app, id)
            assertSame(initial.messages, current.messages)
            assertSame(initial.history, current.history)
            assertTrue(current.isWaitingForAnswer)
            assertEquals(initial.livePromptTokens, current.livePromptTokens)
            assertEquals(initial.cloudHistoryTokens, current.cloudHistoryTokens)
            assertEquals(initial.cloudRequestOverheadTokens, current.cloudRequestOverheadTokens)
            assertEquals(initial.contextBudgetReceiptTokens, current.contextBudgetReceiptTokens)
            assertEquals(route, current.cloudRouteSignature)
            assertEquals(999, app.measuredContextTokens)
            assertEquals(false, call(app, "unmeasuredContextSendAllowed", current, false))
        }
        refresh(1)
        assertEquals(listOf(child), state(app, id).childContexts)
        assertParentUntouched()
        val published = state(app, id)
        val conversations = get(app, "conversationsById")
        refresh(2) // newer registry bookkeeping, identical public telemetry
        assertSame(published, state(app, id))
        assertSame(conversations, get(app, "conversationsById"))
        assertSame(published, app.homeState)
        child = child.copy(contextTokens = 200)
        refresh(3)
        assertNotSame(published, state(app, id))
        assertEquals(200, state(app, id).childContexts.single().contextTokens)
        app.selectContextTask("task", id)
        assertEquals("task", state(app, id).selectedContextTaskId)
        val selected = state(app, id)
        app.selectContextTask("task", id)
        assertSame(selected, state(app, id))
        child = child.copy(status = "completed", statusVersion = 2)
        refresh(4)
        assertEquals("task", state(app, id).selectedContextTaskId)
        val token = reducer.pendingHides().single()
        now += AgentOwnerContextState.HIDE_AFTER_MS
        assertTrue(reducer.expire(token))
        call(app, "publishOwnerContext", id)
        assertTrue(state(app, id).childContexts.isEmpty())
        assertNull(state(app, id).selectedContextTaskId)
        assertEquals(listOf(child), state(app, id).childStatusRoster)
        val hidden = state(app, id)
        child = child.copy(outputTokens = 42)
        refresh(5)
        assertNotSame(hidden, state(app, id)) // hidden roster changes still publish
        assertTrue(state(app, id).childContexts.isEmpty())
        assertEquals(42L, state(app, id).childStatusRoster.single().outputTokens)
        assertParentUntouched()
        // Preserve the private three-parameter reflection entry and its usual question projection.
        call(app, "updateConversation", id, state(app, id), false)
        assertFalse(state(app, id).isWaitingForAnswer)
    }

    @Test fun changeAndRevertCannotResurrectInvalidatedUsageRunOrOldReceipt() = withApp { app, provider ->
        val custom = provider.copy(customHeaders = listOf(CustomHeader("x-test", "one")))
        call(app, "updateSelectionProviders", listOf(custom))
        set(app, "selectedConversationId", "owner")
        val source = AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = false,
            thinkingEnabled = false, providerId = "p", modelId = "m", contextHasStarted = true,
            livePromptTokens = 999, cloudHistoryTokens = 700, cloudRequestOverheadTokens = 50,
            contextBudgetReceiptTokens = 999)
        val route = call(app, "contextRouteSignature", source) as String
        call(app, "updateConversation", "owner", source.copy(cloudRouteSignature = route), false)
        @Suppress("UNCHECKED_CAST")
        (get(app, "runConversationIds") as MutableMap<String, String>)["run"] = "owner"
        @Suppress("UNCHECKED_CAST")
        (get(app, "runUsageRoutes") as MutableMap<String, String>)["run"] = route
        assertEquals(999, app.measuredContextTokens)
        call(app, "updateSelectionProviders", listOf(custom.copy(customHeaders = listOf(CustomHeader("x-test", "two")))))
        assertTrue("run" in (get(app, "invalidatedUsageRuns") as Set<*>))
        assertNull(app.measuredContextTokens)
        assertNull(state(app, "owner").contextBudgetReceiptTokens)
        call(app, "updateSelectionProviders", listOf(custom))
        assertEquals(route, call(app, "contextRouteSignature", source))
        assertTrue("run" in (get(app, "invalidatedUsageRuns") as Set<*>))
        assertNull(app.measuredContextTokens)
        assertNull(state(app, "owner").cloudRouteSignature)
    }

    private fun withApp(block: (AgentAppState, OpenAiCompatibleProviderSetting) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try {
            val app = AgentAppState(context, startupScope)
            val provider = OpenAiCompatibleProviderSetting("p", "Test", "https://example.org/v1", createdAt = 0,
                models = listOf(Model("m", "model", "Model", contextWindow = 100000, createdAt = 0)))
            call(app, "updateSelectionProviders", listOf(provider))
            block(app, provider)
        } finally { startupScope.cancel(); EtaDatabase.closeForTests() }
    }

    // The pre-cache algorithm, including exact JSON order/model normalization/hex formatting.
    private fun originalActualSignature(provider: OpenAiCompatibleProviderSetting): String {
        val fields = org.json.JSONArray().put(provider.id).put(provider.baseUrl).put(provider.sourceType)
            .put(provider.endpointMode).put(provider.systemPrompt).put(provider.authMode).put(provider.apiKey)
            .put(provider.responsesStripReasoningStatus).put(provider.hostedWebSearchEnabled)
            .put(provider.sessionGatewayJson)
            .put(org.json.JSONArray(provider.customHeaders.map { listOf(it.name, it.value) }))
            .put(org.json.JSONArray(provider.customBody.map { listOf(it.key, it.value.toString()) }))
            .put(kotlinx.serialization.json.Json.encodeToString(Model.serializer(),
                provider.models.single().copy(createdAt = 0, displayName = "", sortOrder = 0)))
        return "actual-local-v1:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(fields.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun state(app: AgentAppState, id: String) = call(app, "conversationState", id) as AgentChatHomeUiState
    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
