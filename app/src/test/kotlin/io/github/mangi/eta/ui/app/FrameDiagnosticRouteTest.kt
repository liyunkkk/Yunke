package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.components.FrameDiagnosticPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import org.junit.Assert.*
import org.junit.Test

class FrameDiagnosticRouteTest {
    @Test fun settingsAndChatHaveDistinctStableLabels() {
        assertEquals(FrameDiagnosticPage.Settings, AppRoute.Settings.frameDiagnosticPage())
        assertEquals(FrameDiagnosticPage.Chat, AppRoute.Chat.frameDiagnosticPage())
        assertEquals(FrameDiagnosticPage.SubAgents, AppRoute.SubAgents.frameDiagnosticPage())
    }

    @Test fun routeArgumentsNeverReachDiagnosticLabels() {
        val secret = "private-id-token-url-name"
        val routes = listOf(
            AppRoute.LinuxFiles(secret), AppRoute.McpServerDetail(secret),
            AppRoute.ModelProviderDetail(secret), AppRoute.AssistantEdit(secret),
            AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible, secret),
        )
        routes.forEach { route ->
            assertFalse(route.frameDiagnosticPage().name.contains(secret))
            assertNotEquals(FrameDiagnosticPage.Unknown, route.frameDiagnosticPage())
        }
        assertEquals(AppRoute.ModelProviderDetail("a").frameDiagnosticPage(),
            AppRoute.ModelProviderDetail("b").frameDiagnosticPage())
    }

    @Test fun legacyRoutesDescribeTheScreenActuallyRendered() {
        assertEquals(FrameDiagnosticPage.AgentTaskPreference, AppRoute.VirtualDisplayRecovery.frameDiagnosticPage())
        listOf(AppRoute.SpeechSettings, AppRoute.TtsSettings, AppRoute.VoiceModeSettings).forEach {
            assertEquals(FrameDiagnosticPage.VoiceSettings, it.frameDiagnosticPage())
        }
    }
}
