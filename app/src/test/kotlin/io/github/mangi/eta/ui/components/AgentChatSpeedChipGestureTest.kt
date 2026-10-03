package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 思考图标手势：GPT 速度模型长按切速度、单击仍开旧思考弹窗；非 GPT 无长按语义且不切速度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatSpeedChipGestureTest {
    @get:Rule val compose = createComposeRule()

    private val gptSupported = mutableStateOf(true)
    private val options = mutableStateOf<List<ReasoningEffort>>(emptyList())
    private val mode = mutableStateOf(GptSpeedMode.NORMAL)
    private var cycles = 0
    private var effortChanges = 0

    private lateinit var context: Context

    private fun render() {
        context = RuntimeEnvironment.getApplication()
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ThinkingEffortChip(
                    effort = ReasoningEffort.HIGH,
                    options = options.value,
                    enabled = true,
                    gptSpeedMode = mode.value,
                    gptSpeedSupported = gptSupported.value,
                    onCycleGptSpeedMode = {
                        cycles++
                        mode.value = mode.value.next()
                    },
                    onEffortChange = { effortChanges++ },
                )
            }
        }
    }

    private fun chip() = compose.onNodeWithContentDescription(
        context.getString(R.string.chat_reasoning_effort, ReasoningEffort.HIGH.displayName),
        substring = true,
    )

    private fun reasoningDialogTitle() =
        context.getString(R.string.reasoning_picker_title)

    @Test
    fun gptLongPressCyclesSpeedWhileSingleClickDoesNot() {
        gptSupported.value = true
        options.value = emptyList()
        render()
        chip().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, cycles) }
        chip().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, cycles) }
    }

    @Test
    fun gptKeepsLongPressSemanticsAndStillOpensReasoningDialogOnClick() {
        gptSupported.value = true
        options.value = listOf(ReasoningEffort.OFF, ReasoningEffort.LOW, ReasoningEffort.HIGH)
        render()
        assertTrue(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        chip().performTouchInput { longClick() }
        // 长按只切速度，不触发旧的思考弹窗。
        compose.onNodeWithText(reasoningDialogTitle()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cycles) }
        chip().performTouchInput { click() }
        compose.onNodeWithText(reasoningDialogTitle()).assertExists()
        compose.runOnIdle { assertEquals(1, cycles) }
    }

    @Test
    fun nonGptChipHasNoSpeedLongPressSemantics() {
        gptSupported.value = false
        options.value = listOf(ReasoningEffort.OFF, ReasoningEffort.LOW, ReasoningEffort.HIGH)
        render()
        assertFalse(chip().fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        chip().performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(0, cycles)
            assertEquals(0, effortChanges)
        }
    }
}
