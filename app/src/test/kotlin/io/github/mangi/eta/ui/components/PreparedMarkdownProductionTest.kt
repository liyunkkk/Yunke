package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Executes ChatMessageItem -> target -> Default parser -> prepared block -> actual text host.
 * CI correctness/layout coverage, not device performance or a frame-rate claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreparedMarkdownProductionTest {
    @get:Rule val compose = createComposeRule()
    @Before fun initPrefs() { Prefs.initLocal(RuntimeEnvironment.getApplication()) }

    @Test fun realWorkerReusesPlainPrefixAndRefreshesThemeCorrectionAndTerminal() {
        val retained = StreamingMarkdownState()
        val prefix = "Stable **bold** and `code`.\n\n"
        val message = mutableStateOf(AgentMessageUi("prepared", prefix + "Tail", isStreaming = true))
        val paused = mutableStateOf(true)
        val dark = mutableStateOf(false)
        val width = mutableStateOf(340.dp)
        val scale = mutableStateOf(1f)
        compose.setContent {
            MiuixTheme(colors = if (dark.value) darkColorScheme() else lightColorScheme()) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                    Column(Modifier.width(width.value)) {
                        ChatMessageItem(
                            message.value, remember { ChatMessageActions() }, false,
                            retainedStreamingState = retained, showCopyAction = false,
                            isPaused = paused.value,
                        )
                    }
                }
            }
        }
        fun await(source: String, terminal: Boolean = false) {
            compose.waitUntil(15_000) {
                retained.snapshot?.let { it.originalSource == source && it.isComplete == terminal &&
                    it.preparedBlocks.firstOrNull()?.spec != null } == true
            }
            compose.waitForIdle()
        }
        await(message.value.content)
        val first = requireNotNull(retained.snapshot).preparedBlocks.first()
        assertNotNull(first.text(first.node, requireNotNull(first.spec).typography.paragraph.toSpanStyle()))
        compose.onNodeWithText("Stable bold and code.", useUnmergedTree = true).assertExists()
        compose.runOnIdle { message.value = message.value.copy(content = prefix + "Tail grows") }
        await(message.value.content)
        assertSame(first, requireNotNull(retained.snapshot).preparedBlocks.first())
        compose.onNodeWithText("Tail grows", useUnmergedTree = true).assertExists()

        compose.runOnIdle { dark.value = true }
        compose.waitUntil(15_000) { retained.snapshot?.preparedBlocks?.firstOrNull()?.spec != first.spec }
        compose.waitForIdle()
        val themed = requireNotNull(retained.snapshot).preparedBlocks.first()
        assertNotSame(first, themed)
        assertNotNull(themed.text(themed.node, requireNotNull(themed.spec).typography.paragraph.toSpanStyle()))
        compose.runOnIdle { width.value = 220.dp; scale.value = 1.3f }
        compose.waitForIdle()
        compose.onNodeWithText("Stable bold and code.", useUnmergedTree = true).assertExists()

        compose.runOnIdle { message.value = message.value.copy(content = "Corrected **prefix**.\n\nNew tail") }
        await(message.value.content)
        assertNotSame(themed, requireNotNull(retained.snapshot).preparedBlocks.first())
        compose.onNodeWithText("Corrected prefix.", useUnmergedTree = true).assertExists()
        val pending = requireNotNull(retained.snapshot)
        compose.runOnIdle { message.value = message.value.copy(isStreaming = false); paused.value = false }
        await(message.value.content, terminal = true)
        val final = requireNotNull(retained.snapshot)
        assertNotSame(pending.preparedBlocks.first(), final.preparedBlocks.first())
        assertEquals(message.value.content, final.renderedSource)
        compose.onNodeWithText("New tail", useUnmergedTree = true).assertExists()
    }
}
