package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Bounded draft UI regressions; owner transactions/memory implementation are tested by the data suite. */
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentProfileConfigDialogTest {
    @get:Rule val compose = createComposeRule()
    private val model = Model(id = "draft-record", modelId = "draft-api", displayName = "草稿测试模型")
    private val provider = OpenAiCompatibleProviderSetting(id = "draft-provider", name = "草稿测试提供商",
        baseUrl = "https://example.invalid", models = listOf(model))
    private val profile = SubAgentProfile("draft-existing", "草稿测试代理", providerId = provider.id, modelId = model.id)

    private class FailOncePreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var failNext = false
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean = if (failNext) { failNext = false; false } else editor.commit()
            }
        }
    }

    @Test fun emptyCatalogUseCurrentAndAddCancelAreZeroWrite() {
        val fixture = SubAgentUiFixture(profiles = emptyList(), enabled = false)
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        compose.setSubAgentContent(fixture) { MiuixTheme(colors = lightColorScheme()) {
            ConversationCollaborationDialog(true, false, {}, {})
        } }
        compose.runOnIdle { assertTrue(fixture.repository.presets().isEmpty()) }
        compose.onNodeWithText("使用当前配置").performScrollTo().performClick()
        compose.onNodeWithContentDescription("添加子代理").performScrollTo().performClick()
        compose.onNodeWithContentDescription("名称").performTextReplacement("尚未保存")
        compose.onNodeWithText("确认").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(before, fixture.snapshot())
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @Test fun localFieldsAndNestedTierCancelDoNotWrite() {
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider))
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, profile))
        compose.setSubAgentContent(fixture) { MiuixTheme(colors = lightColorScheme()) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(provider), onDismiss = { open.value = false })
        } }
        compose.onNodeWithContentDescription("名称").performTextReplacement("改名但取消")
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().performClick()
        compose.onNodeWithText("复杂任务").performClick()
        compose.runOnIdle { assertEquals(SubAgentTaskTier.COMPLEX, session.draft.tier) }
        compose.onNodeWithContentDescription("并行上限（0 为不限）").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertFalse(open.value)
            assertEquals(before, fixture.snapshot())
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @Test fun failureKeepsDraftAndExplicitRetryThenSavedCloses() {
        lateinit var preferences: FailOncePreferences
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider), preferenceTransform = {
            FailOncePreferences(it).also { wrapped -> preferences = wrapped }
        })
        val before = fixture.snapshot()
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, profile))
        compose.setSubAgentContent(fixture) { MiuixTheme(colors = lightColorScheme()) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(provider), onDismiss = { open.value = false })
        } }
        compose.onNodeWithContentDescription("名称").performTextReplacement("成功才关闭")
        compose.runOnIdle { preferences.failNext = true }
        compose.onNodeWithText("确认").assertIsEnabled().performClick()
        compose.waitUntil { !session.submitting }
        compose.runOnIdle { assertTrue(open.value); assertEquals("成功才关闭", session.name); assertEquals(before, fixture.snapshot()) }
        compose.onNodeWithText("未保存：配置或模型已变更", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("重试恢复配置（保留草稿）").performScrollTo().performClick()
        compose.onNodeWithText("确认").assertIsEnabled().performClick()
        compose.waitUntil { !open.value }
        compose.runOnIdle { assertEquals("成功才关闭", fixture.snapshot().profiles.single().name) }
    }

    @Test fun ownerGateInvalidationDropsOpenDraftWithoutWriting() {
        val allowed = mutableStateOf(true)
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider), canEdit = { allowed.value })
        val before = fixture.snapshot()
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, profile))
        compose.setSubAgentContent(fixture) { MiuixTheme(colors = lightColorScheme()) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(provider), enabled = allowed.value,
                onDismiss = { open.value = false })
        } }
        compose.onNodeWithContentDescription("名称").performTextReplacement("失效不写")
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithText("确认").assertDoesNotExist()
        compose.runOnIdle { assertFalse(open.value); assertEquals(before, fixture.snapshot()) }
    }

    @Test fun existingParallelUsesActualOwnerAndOnlyExplicitEditCreatesChange() {
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider))
        val pool = SubAgentParallelModel(provider.id, model.modelId)
        fixture.repository.update(fixture.owner) { it.copy(parallelLimits = mapOf(pool to 0)) }
        // A new editor snapshot is deliberately captured after the owner's actual limit changed.
        val editor = ConversationSubAgentEditor(fixture.owner, fixture.repository, { provider }) { true }
        val session = requireNotNull(SubAgentProfileDraftSession.open(editor, profile))
        session.bindParallel(resolveSubAgentProfileConfig(profile, listOf(provider)))
        assertEquals("0", session.parallelValue)
        assertNull(session.parallelChange())
        session.editParallel("2")
        assertEquals(SubAgentParallelLimitChange(pool, 2, 0), session.parallelChange())
        session.dismiss()
        assertEquals(0, fixture.snapshot().parallelLimit(pool))
    }
}
