package io.github.mangi.eta.ui

import android.content.Intent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class OAuthRendererExitTest {
    @Test fun rendererExitDetachesAndFinishesLoginWithoutReusingDeadView() {
        for (crashed in listOf(true, false)) {
            val pending = CompletableDeferred<Pair<String, String?>>()
            val pendingField = OAuthLoginActivity::class.java.getDeclaredField("pending").apply { isAccessible = true }
            pendingField.set(null, pending)
            val controller = Robolectric.buildActivity(OAuthLoginActivity::class.java,
                Intent().putExtra("oauth_url", "https://example.com/login")).create()
            val activity = controller.get()
            val field = OAuthLoginActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
            val web = field.get(activity) as WebView
            val client = web.webViewClient
            val detail = object : RenderProcessGoneDetail() {
                override fun didCrash() = crashed
                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
            }
            assertTrue(client.onRenderProcessGone(web, detail))
            assertNull(field.get(activity))
            assertNull(web.parent)
            assertTrue(shadowOf(web).wasDestroyCalled())
            assertTrue(activity.isFinishing)
            assertTrue(pending.isCancelled)
            assertNull(pendingField.get(null))
            assertTrue(client.onRenderProcessGone(web, detail))
            controller.destroy()
        }
    }
}
