package io.github.mangi.eta.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [WindowQueryShadow::class])
class AccessibilityWindowObservationTest {
    @Test
    fun mainDisplayQueryIgnoresActiveRootFromSecondaryDisplay() = withService { service ->
        val mainWindow = window(1, 0, "main.app")
        val secondaryWindow = window(11, 11, "secondary.app")
        shadowOf(service).setWindowsOnDisplay(0, listOf(mainWindow))
        shadowOf(service).setWindowsOnDisplay(11, listOf(secondaryWindow))
        shadowOf(service).setRootInActiveWindow(secondaryWindow.root)

        val observation = query { service.observeNodeSnapshot(1, displayId = 0, includeNodes = false) }

        assertEquals(AgentAccessibilityService.NodeSnapshotStatus.AVAILABLE, observation.status)
        assertEquals("main.app", observation.window?.packageName)
        assertEquals(0, observation.window?.displayId)
        assertNull(observation.snapshot)
        assertFalse(WindowQueryShadow.queriedOnMain)
    }

    @Test
    fun inaccessibleTopWindowDoesNotFallBackToWindowBehindIt() = withService { service ->
        val behind = window(1, 0, "behind.app", layer = 1)
        val top = window(2, 0, null, layer = 2)
        shadowOf(service).setWindowsOnDisplay(0, listOf(behind, top))

        val observation = query { service.observeNodeSnapshot(1, includeNodes = false) }

        assertEquals(AgentAccessibilityService.NodeSnapshotStatus.ROOT_UNAVAILABLE, observation.status)
        assertEquals(2, observation.window?.windowId)
        assertNull(observation.window?.packageName)
    }

    @Test
    fun missingTargetDisplayNeverUsesGlobalActiveRoot() = withService { service ->
        shadowOf(service).setRootInActiveWindow(window(1, 0, "main.app").root)
        val observation = query { service.observeNodeSnapshot(1, displayId = 11, includeNodes = false) }
        assertEquals(AgentAccessibilityService.NodeSnapshotStatus.NO_WINDOW, observation.status)
        assertNull(observation.window)
    }

    @Test
    fun windowEventsNeverEnumerateWindows() = withService { service ->
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            shadowOf(this).setWindowId(11)
        })
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
            shadowOf(this).setWindowId(1)
        })
        assertEquals(0, WindowQueryShadow.queries)
    }

    @Test
    fun rebindingSameServiceInvalidatesPreviousConnectionToken() = withService { service ->
        val previous = ReflectionHelpers.getField<Long>(service, "serviceToken")
        service.onUnbind(null)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        assertNotEquals(previous, ReflectionHelpers.getField<Long>(service, "serviceToken"))
    }

    @Test
    fun mainThreadObservationReturnsBusyWithoutRemoteReads() = withService { service ->
        assertEquals(AgentAccessibilityService.NodeSnapshotStatus.BUSY, service.observeNodeSnapshot(1).status)
        assertEquals(0, WindowQueryShadow.queries)
    }

    @Test
    fun unboundServiceRejectsObservationsAndActions() = withService { service ->
        service.onUnbind(null)
        assertEquals(AgentAccessibilityService.NodeSnapshotStatus.SERVICE_UNAVAILABLE,
            query { service.observeNodeSnapshot(1) }.status)
        assertFalse(service.copyToClipboard("stale").ok)
    }

    @Test
    fun secondaryDisplayKeepsYunkeNullRootCandidateFallback() = withService { service ->
        val top = window(12, 11, null, layer = 2)
        val behind = window(11, 11, "secondary.app", layer = 1)
        shadowOf(service).setWindowsOnDisplay(11, listOf(top, behind))
        val observation = query { service.observeNodeSnapshot(1, displayId = 11, includeNodes = false) }
        assertEquals(AgentAccessibilityService.NodeSnapshotStatus.AVAILABLE, observation.status)
        assertEquals("secondary.app", observation.window?.packageName)
        assertEquals(11, observation.window?.displayId)
        assertFalse(WindowQueryShadow.queriedOnMain)
    }

    private fun window(id: Int, displayId: Int, packageName: String?, layer: Int = 1): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { window ->
            shadowOf(window).apply {
                setId(id)
                setDisplayId(displayId)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setLayer(layer)
                if (packageName != null) setRoot(AccessibilityNodeInfo.obtain().apply { this.packageName = packageName })
            }
        }

    private fun <T> query(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { block() }.get(3, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun withService(block: (AgentAccessibilityService) -> Unit) {
        WindowQueryShadow.queries = 0
        WindowQueryShadow.queriedOnMain = false
        val controller = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "onServiceConnected")
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }
}

@Implements(AccessibilityService::class)
class WindowQueryShadow : ShadowAccessibilityService() {
    @Implementation
    override fun getWindowsOnAllDisplays(): SparseArray<List<AccessibilityWindowInfo>> {
        queries++
        queriedOnMain = queriedOnMain || Looper.myLooper() == Looper.getMainLooper()
        return super.getWindowsOnAllDisplays()
    }

    companion object {
        var queries = 0
        var queriedOnMain = false
    }
}
