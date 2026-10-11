package io.github.mangi.eta.ui.haptics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Display
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用是否有界面在主屏上可见。只数主屏上的 Activity，副屏锚点不算前台。
 * 没装上（单测或非主进程）时一律当作前台，不改变原有震动。
 */
internal object AppForeground : Application.ActivityLifecycleCallbacks {
    private val visible = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var installed = false

    @Volatile
    private var lastForeground = true

    val isForeground: Boolean
        get() = !installed || visible.isNotEmpty()

    fun install(application: Application) {
        if (installed) return
        installed = true
        lastForeground = isForeground
        application.registerActivityLifecycleCallbacks(this)
    }

    /** 注册前台/后台切换监听；只在状态真正翻转时回调，重复注册同一 lambda 不会重复触发。 */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    private fun publish() {
        val now = isForeground
        if (now == lastForeground) return
        lastForeground = now
        listeners.forEach { runCatching { it(now) } }
    }

    override fun onActivityStarted(activity: Activity) {
        val displayId = runCatching { activity.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
        if (displayId == Display.DEFAULT_DISPLAY) visible += System.identityHashCode(activity)
        publish()
    }

    override fun onActivityStopped(activity: Activity) {
        visible -= System.identityHashCode(activity)
        publish()
    }

    override fun onActivityDestroyed(activity: Activity) {
        visible -= System.identityHashCode(activity)
        publish()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
