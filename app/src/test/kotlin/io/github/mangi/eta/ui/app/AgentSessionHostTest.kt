package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentSessionHostTest {
    private lateinit var application: Application

    @Before fun setUp() {
        AgentSessionHost.resetForTests()
        application = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        application.deleteDatabase("eta.db")
    }

    @After fun tearDown() {
        AgentSessionHost.resetForTests()
        EtaDatabase.closeForTests()
    }

    @Test fun clearingActualViewModelStoreKeepsProcessScopeAndSameState() {
        val host = AgentSessionHost.get(application)
        val first = AgentAppViewModel(application)
        val store = ViewModelStore()
        store.put("agent", first)
        val runWait = host.scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        store.clear()
        assertTrue(runWait.isActive)
        val next = AgentAppViewModel(application)
        assertSame(first.state, next.state)
        assertSame(host.state, next.state)
        runWait.cancel()
    }
}
