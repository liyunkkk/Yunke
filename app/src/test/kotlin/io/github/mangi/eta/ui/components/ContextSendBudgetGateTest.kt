package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ContextSendBudgetGateTest {
    @get:Rule val compose = createComposeRule()

    private data class Scenario(val measured: Int?, val auto: Boolean, val tokens: Int?, val window: Int?)

    @Test fun allPolicyBranchesMatchPreviousDecisionAndSkipUnusedWork() {
        val scenario = mutableStateOf(Scenario(null, true, 0, 1000))
        var calls = 0
        var blocked = true
        compose.setContent {
            val s = scenario.value
            val result = contextSendBlocked(s.measured, s.auto) {
                calls++
                AgentContextUsageUi(s.tokens, s.window)
            }
            SideEffect { blocked = result }
        }
        for (measured in listOf<Int?>(null, 0, 100)) {
            for (auto in listOf(false, true)) {
                for (tokens in listOf<Int?>(null, 980, 990, 1000, 1500)) {
                    for (window in listOf<Int?>(null, 0, 1000)) {
                        val s = Scenario(measured, auto, tokens, window)
                        var before = 0
                        compose.runOnIdle { before = calls; scenario.value = s }
                        compose.runOnIdle {
                            val oldDecision = measured != null &&
                                shouldBlockSendForContextWindow(auto, AgentContextUsageUi(tokens, window))
                            assertEquals(s.toString(), oldDecision, blocked)
                            if (measured == null || auto) assertEquals(s.toString(), before, calls)
                        }
                    }
                }
            }
        }
    }

    @Test fun enablingGateUsesCurrentInputsAndRemembersOnlyWhileNeeded() {
        val measured = mutableStateOf<Int?>(null)
        val auto = mutableStateOf(true)
        val history = mutableStateOf(100)
        val draft = mutableStateOf(0)
        val unrelated = mutableStateOf(0)
        var rawCalls = 0
        var localCalls = 0
        var budgetCalls = 0
        var blocked = false
        var rendered = -1
        compose.setContent {
            val tick = unrelated.value
            val result = contextSendBlocked(measured.value, auto.value) {
                val raw = remember(history.value) { rawCalls++; history.value }
                val local = remember(history.value) { localCalls++; history.value }
                remember(raw, local, draft.value) {
                    budgetCalls++
                    AgentContextUsageUi(maxOf(raw, local) + draft.value, 1000)
                }
            }
            SideEffect { blocked = result; rendered = tick }
        }
        compose.runOnIdle {
            assertEquals(0, rawCalls)
            assertEquals(0, localCalls)
            assertEquals(0, budgetCalls)
            history.value = 980
            draft.value = 20
            measured.value = 0 // Non-null zero must preserve old gate semantics.
            unrelated.value++
        }
        compose.runOnIdle {
            assertEquals(0, budgetCalls)
            auto.value = false
        }
        compose.runOnIdle {
            assertTrue(blocked)
            assertEquals(1, rawCalls)
            assertEquals(1, localCalls)
            assertEquals(1, budgetCalls)
            unrelated.value++
        }
        compose.runOnIdle {
            assertEquals(2, rendered)
            assertEquals(1, budgetCalls)
            draft.value = 0
        }
        compose.runOnIdle {
            assertFalse(blocked)
            assertEquals(1, rawCalls)
            assertEquals(1, localCalls)
            assertEquals(2, budgetCalls)
            auto.value = true
        }
        compose.runOnIdle {
            assertFalse(blocked)
            history.value = 1200
            draft.value = 30
            unrelated.value++
        }
        compose.runOnIdle {
            assertEquals(2, budgetCalls)
            auto.value = false
        }
        compose.runOnIdle {
            assertTrue(blocked)
            assertEquals(2, rawCalls)
            assertEquals(2, localCalls)
            assertEquals(3, budgetCalls)
            measured.value = null
        }
        compose.runOnIdle { assertFalse(blocked); assertEquals(3, budgetCalls) }
    }
}
