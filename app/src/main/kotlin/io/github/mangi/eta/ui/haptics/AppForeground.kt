package io.github.mangi.eta.ui.haptics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Display
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用是否有界面在主屏上可见。只数主屏上的 Activity，副屏锚点不算前台。
 * 没装上（单测或非主进程）时一律当作前台，不改变原有震动。
 */
internal object AppForeground : Application.ActivityLifecycleCallbacks {
    private val visible = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    @Volatile
    private var installed = false

    val isForeground: Boolean
        get() = !installed || visible.isNotEmpty()

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val displayId = runCatching { activity.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
        if (displayId == Display.DEFAULT_DISPLAY) visible += System.identityHashCode(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        visible -= System.identityHashCode(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        visible -= System.identityHashCode(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
