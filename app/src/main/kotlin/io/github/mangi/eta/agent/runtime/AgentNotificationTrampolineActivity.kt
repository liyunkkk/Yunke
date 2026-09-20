package io.github.mangi.eta.agent.runtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.MainActivity

class AgentNotificationTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        try {
            val source = intent.getStringExtra(EXTRA_SOURCE)
            if (source == SOURCE_OVERLAY || EtaAssistantOverlayService.isServiceActive()) {
                EtaAssistantOverlayService.show(this)
            } else {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(mainIntent)
            }
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_SOURCE = "io.github.mangi.eta.extra.NOTIFICATION_SOURCE"
        const val SOURCE_OVERLAY = "overlay"
        const val SOURCE_MAIN = "main"
    }
}
