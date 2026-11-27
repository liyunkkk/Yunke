package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.AppearanceAppIcon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class AppIconManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun pearlIsTheOnlyDefaultLauncherEntry() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val launcherActivities = context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.name }
            .toSet()

        assertEquals(setOf("io.github.mangi.eta.ui.MainActivityPearl"), launcherActivities)
        assertEquals(AppearanceAppIcon.PEARL, AppIconManager.currentIcon(context))
    }

    @Test
    fun selectingAnotherIconUpdatesTheActiveLauncherEntry() {
        AppIconManager.apply(context, AppearanceAppIcon.OBSIDIAN)

        assertEquals(AppearanceAppIcon.OBSIDIAN, AppIconManager.currentIcon(context))
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val launcherActivities = context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.name }
            .toSet()
        assertEquals(setOf("io.github.mangi.eta.ui.MainActivityObsidian"), launcherActivities)
    }
}
