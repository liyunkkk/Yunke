package io.github.mangi.eta.ui

import android.content.Intent
import android.os.Parcelable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)
    private var inboundShareUris by mutableStateOf<List<String>>(emptyList())
    private var appliedPredictiveBackEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        updateAssistantHandoff(intent)
        consumeShareIntent(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = assistantConversationKey,
                        inboundShareUris = inboundShareUris,
                        onAssistantConversationOpened = { opened ->
                            assistantConversationKey = null
                            if (opened) {
                                EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                        onInboundShareConsumed = { inboundShareUris = emptyList() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
        consumeShareIntent(intent)
    }

    private fun consumeShareIntent(intent: Intent?) {
        val uris = inboundUris(intent)
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            val flags = intent?.flags ?: 0
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        inboundShareUris = uris.map { it.toString() }
        intent?.removeExtra(Intent.EXTRA_STREAM)
        intent?.data = null
    }

    private fun inboundUris(intent: Intent?): List<android.net.Uri> {
        if (intent == null) return emptyList()
        val fromStream = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.shareUriExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.shareUriListExtra()
            else -> emptyList()
        }
        val fromData = intent.data?.takeIf {
            intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND
        }
        return (fromStream + listOfNotNull(fromData)).distinct()
    }

    private fun Intent.shareUriExtra(): android.net.Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.shareUriListExtra(): List<android.net.Uri> {
        val values: ArrayList<out Parcelable>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
        }
        return values.orEmpty().mapNotNull { it as? android.net.Uri }
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        @Volatile
        var isForeground = false
            private set
    }
}
