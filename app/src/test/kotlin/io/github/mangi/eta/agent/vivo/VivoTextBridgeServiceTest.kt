package io.github.mangi.eta.agent.vivo

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import io.github.mangi.eta.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VivoTextBridgeServiceTest {
    @Test fun normalBuildDoesNotExposeUsableBinder() {
        val controller = Robolectric.buildService(VivoTextBridgeService::class.java).create()
        try {
            val service = controller.get()
            assertFalse(service.resources.getBoolean(R.bool.vivo_text_bridge_enabled))
            assertNull(service.onBind(Intent()))
        } finally {
            controller.destroy()
        }
    }

    @Test fun manifestDisablesComponentBeforeBinding() {
        val controller = Robolectric.buildService(VivoTextBridgeService::class.java).create()
        try {
            val service = controller.get()
            @Suppress("DEPRECATION")
            val info = service.packageManager.getServiceInfo(
                ComponentName(service, VivoTextBridgeService::class.java),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertFalse(info.enabled)
            assertTrue(info.exported)
        } finally {
            controller.destroy()
        }
    }
}
